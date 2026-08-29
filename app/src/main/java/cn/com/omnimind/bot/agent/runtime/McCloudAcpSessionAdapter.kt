package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.mccloud.McCloud
import cn.com.omnimind.baselib.mccloud.McCloudEvents
import cn.com.omnimind.baselib.mccloud.McCloudTask
import cn.com.omnimind.baselib.mccloud.McCloudTaskChunk
import cn.com.omnimind.baselib.mccloud.McCloudTaskPage
import cn.com.omnimind.baselib.mccloud.McCloudTaskRounds
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal const val MC_CLOUD_SESSION_PREFIX = "mccloud:"
internal const val MC_CLOUD_AGENT_ID = "mccloud"
private const val MC_CLOUD_CONNECT_TIMEOUT_MS = 15_000L
private const val MC_CLOUD_CANCEL_TIMEOUT_MS = 10_000L

internal interface McCloudAcpGateway {
    suspend fun listTasks(page: Int, size: Int): McCloudTaskPage
    suspend fun getTaskDetail(taskId: String): McCloudTask
    suspend fun getTaskRounds(taskId: String, limit: Int): McCloudTaskRounds
    fun openTaskStream(taskId: String, mode: String): String
    fun sendTaskStreamMessage(taskId: String, data: String): Boolean
    fun closeTaskStream(taskId: String)
    fun subscribe(listener: (Map<String, Any?>) -> Unit): () -> Unit
}

private class DefaultMcCloudAcpGateway : McCloudAcpGateway {
    override suspend fun listTasks(page: Int, size: Int) =
        McCloud.get().projects.listTasks(page = page, size = size)

    override suspend fun getTaskDetail(taskId: String) =
        McCloud.get().projects.getTaskDetail(taskId)

    override suspend fun getTaskRounds(taskId: String, limit: Int) =
        McCloud.get().projects.getTaskRounds(taskId, limit = limit)

    override fun openTaskStream(taskId: String, mode: String): String =
        McCloud.get().tasks.open(taskId, mode)

    override fun sendTaskStreamMessage(taskId: String, data: String): Boolean =
        McCloud.get().tasks.send(taskId, data)

    override fun closeTaskStream(taskId: String) {
        McCloud.get().tasks.close(taskId)
    }

    override fun subscribe(listener: (Map<String, Any?>) -> Unit): () -> Unit =
        McCloudEvents.subscribe(listener)
}

/** Projects McCloud tasks onto the shared ACP session boundary. */
internal class McCloudAcpSessionAdapter(
    private val gateway: McCloudAcpGateway = DefaultMcCloudAcpGateway(),
    private val publish: (Map<String, Any?>) -> Unit,
    private val gson: Gson = Gson(),
    private val cancelTimeoutMs: Long = MC_CLOUD_CANCEL_TIMEOUT_MS,
) {
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
    private val connected = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val activeTurns = ConcurrentHashMap<String, CloudTurn>()
    private val requestTurns = ConcurrentHashMap<String, CloudTurn>()
    private val sessionSequences = ConcurrentHashMap<String, AtomicLong>()
    private val fallbackToolIds = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val latestTurnSequences = ConcurrentHashMap<String, Long>()
    @Suppress("unused")
    private val unsubscribe = gateway.subscribe(::onCloudEvent)

    fun ownsSession(args: Map<String, Any?>): Boolean =
        cloudTaskId(args.stringValue("sessionId") ?: args.stringValue("threadId")) != null

    suspend fun listSessions(args: Map<String, Any?>): Map<String, Any?> {
        val size = (args.longValue("limit")?.toInt() ?: 20).coerceIn(1, 100)
        val page = gateway.listTasks(page = 1, size = size)
        return mapOf(
            "sessions" to page.tasks.map(::taskSession),
            "nextCursor" to if (page.pageInfo.hasNextPage) "mccloud:page:2" else null,
        )
    }

    fun mergeSessions(
        base: Map<String, Any?>?,
        cloud: Map<String, Any?>?,
    ): Map<String, Any?> {
        val baseSessions = base?.get("sessions") as? List<*> ?: emptyList<Any?>()
        val cloudSessions = cloud?.get("sessions") as? List<*> ?: emptyList<Any?>()
        return LinkedHashMap(base.orEmpty()).apply {
            put("sessions", baseSessions + cloudSessions)
            cloud?.get("nextCursor")?.let { put("mcCloudNextCursor", it) }
        }
    }

    suspend fun loadSession(args: Map<String, Any?>): Map<String, Any?> {
        val taskId = requireTaskId(args)
        val sessionId = sessionId(taskId)
        val task = gateway.getTaskDetail(taskId)
        val rounds = gateway.getTaskRounds(taskId, limit = 10)
        rounds.chunks.forEach { chunk ->
            mapFrame(taskId, chunk.asFrame(), replay = true)?.let(publish)
        }
        if (task.status == "processing") gateway.openTaskStream(taskId, "attach")
        return taskSession(task) + mapOf(
            "loaded" to true,
            "replayed" to rounds.chunks.size,
            "hasMore" to rounds.hasMore,
            "nextCursor" to rounds.nextCursor,
            "sessionId" to sessionId,
            "threadId" to sessionId,
        )
    }

    suspend fun promptSession(args: Map<String, Any?>): Map<String, Any?> {
        val taskId = requireTaskId(args)
        val sessionId = sessionId(taskId)
        val requestId = args.stringValue("requestId")?.takeIf(String::isNotBlank)
        val requestKey = requestId?.let { "$taskId|$it" }
        requestKey?.let { key ->
            requestTurns[key]?.let { return it.response(completed = it.finished.isCompleted, deduplicated = true) }
        }
        check(activeTurns[taskId] == null) { "McCloud session already has an active turn" }
        val turn = CloudTurn(
            sessionId = sessionId,
            turnId = latestTurnSequences[taskId]
                ?.let { stableId("turn", taskId, (it + 1L).toString()) }
                ?: stableId("turn", taskId, requestId ?: UUID.randomUUID().toString()),
        )
        check(activeTurns.putIfAbsent(taskId, turn) == null) {
            "McCloud session already has an active turn"
        }
        requestKey?.let {
            requestTurns[it] = turn
            if (requestTurns.size > 256) requestTurns.keys.firstOrNull()?.let(requestTurns::remove)
        }
        val ready = CompletableDeferred<Unit>()
        connected[taskId] = ready
        var submitted = false
        try {
            gateway.openTaskStream(taskId, "new")
            withTimeout(MC_CLOUD_CONNECT_TIMEOUT_MS) { ready.await() }
            val text = promptText(args)
            require(text.isNotBlank()) { "prompt is required" }
            check(gateway.sendTaskStreamMessage(taskId, userInputFrame(text))) {
                "McCloud task stream rejected the prompt"
            }
            submitted = true
            return turn.finished.await().let { stopReason ->
                turn.response(completed = true) + mapOf("stopReason" to stopReason)
            }
        } finally {
            if (!submitted) requestKey?.let(requestTurns::remove)
            connected.remove(taskId, ready)
            activeTurns.remove(taskId, turn)
        }
    }

    suspend fun cancelSession(args: Map<String, Any?>): Map<String, Any?> {
        val taskId = requireTaskId(args)
        val turn = activeTurns[taskId]
        if (turn == null) {
            return mapOf(
                "ok" to true,
                "cancelled" to false,
                "sessionId" to sessionId(taskId),
                "threadId" to sessionId(taskId),
            )
        }
        turn.cancelling = true
        val sent = gateway.sendTaskStreamMessage(taskId, controlFrame("user-cancel"))
        if (!sent) {
            turn.cancelling = false
            return turn.response(completed = false) + mapOf("ok" to false, "cancelled" to false)
        }
        return try {
            val stopReason = withTimeout(cancelTimeoutMs) { turn.finished.await() }
            turn.response(completed = true) + mapOf(
                "ok" to true,
                "cancelled" to (stopReason == "cancelled" || stopReason == "end_turn"),
                "stopReason" to stopReason,
            )
        } catch (_: TimeoutCancellationException) {
            gateway.closeTaskStream(taskId)
            turn.finished.complete("cancelled")
            turn.response(completed = true) + mapOf(
                "ok" to false,
                "cancelled" to false,
                "timedOut" to true,
                "stopReason" to "cancelled",
            )
        }
    }

    private fun onCloudEvent(event: Map<String, Any?>) {
        val taskId = event.stringValue("taskId") ?: return
        when (event.stringValue("type")) {
            "taskConnected" -> connected[taskId]?.complete(Unit)
            "taskMessage" -> {
                val frame = event.stringValue("data")?.let(::parseMap) ?: return
                mapFrame(taskId, frame, replay = false)?.let(publish)
                when (frame.stringValue("type")) {
                    "task-ended" -> finish(taskId, "end_turn")
                    "task-error", "error" -> finish(taskId, "error")
                }
            }
            "taskClosed" -> activeTurns[taskId]
                ?.takeUnless { it.cancelling }
                ?.finished
                ?.completeExceptionally(IllegalStateException("McCloud task stream closed before a terminal frame"))
        }
    }

    private fun finish(taskId: String, stopReason: String) {
        activeTurns[taskId]?.finished?.complete(stopReason)
    }

    private fun mapFrame(
        taskId: String,
        frame: Map<String, Any?>,
        replay: Boolean,
    ): Map<String, Any?>? {
        val sessionId = sessionId(taskId)
        val frameType = frame.stringValue("type") ?: return null
        val frameSeq = frame.longValue("seq")?.takeIf { it > 0 }
        val turnSeq = frame.longValue("turn_seq")?.takeIf { it > 0 }
        if (turnSeq != null) latestTurnSequences.merge(taskId, turnSeq, ::maxOf)
        val activeTurn = activeTurns[taskId]
        val stableTurnId = turnSeq?.let { stableId("turn", taskId, it.toString()) }
        if (stableTurnId != null && activeTurn != null) activeTurn.turnId = stableTurnId
        val turnId = stableTurnId ?: activeTurn?.turnId ?: stableId("turn", taskId, "unknown")
        val sequence = frameSeq ?: sessionSequences
            .computeIfAbsent(sessionId) { AtomicLong() }
            .incrementAndGet()
        val eventKey = if (frameSeq != null) {
            "$frameSeq:$frameType:${frame.stringValue("kind").orEmpty()}"
        } else {
            "$sequence:${stableId("frame", taskId, turnId, frameType, frame["data"].toString())}"
        }
        val common = linkedMapOf<String, Any?>(
            "eventId" to "$sessionId:$eventKey",
            "sequence" to sequence,
            "workspaceId" to "default",
            "threadId" to sessionId,
            "sessionId" to sessionId,
            "turnId" to turnId,
            "conversationId" to cloudConversationId(taskId),
            "agentId" to MC_CLOUD_AGENT_ID,
            "agentName" to "MonkeyCode Cloud",
            "runtime" to "mccloud",
            "replay" to replay.takeIf { it },
        )
        val data = decodeFrameData(frame["data"])
        val mapped = when {
            frameType == "task-running" && frame.stringValue("kind") == "acp_event" -> {
                val rawUpdate = data?.mapValue("update").orEmpty()
                if (rawUpdate.isEmpty()) return null
                val update = stableUpdateIds(rawUpdate, taskId, turnId, eventKey)
                common + mapOf(
                    "method" to "session/update",
                    "params" to mapOf("sessionId" to sessionId, "update" to update),
                )
            }
            frameType == "user-input" -> {
                val text = decodeBase64(data?.stringValue("content")).orEmpty()
                val update = mapOf(
                    "sessionUpdate" to "user_message_chunk",
                    "messageId" to stableId("message", taskId, turnId, "user"),
                    "content" to mapOf("type" to "text", "text" to text),
                )
                common + mapOf(
                    "method" to "session/update",
                    "params" to mapOf("sessionId" to sessionId, "update" to update),
                )
            }
            frameType == "task-started" -> common + mapOf(
                "method" to "turn/started",
                "params" to mapOf("threadId" to sessionId, "turnId" to turnId),
            )
            frameType == "task-ended" -> common + mapOf(
                "method" to "turn/completed",
                "params" to mapOf("threadId" to sessionId, "turnId" to turnId),
            )
            frameType == "task-error" || frameType == "error" -> common + mapOf(
                "method" to "turn/failed",
                "params" to mapOf(
                    "threadId" to sessionId,
                    "turnId" to turnId,
                    "willRetry" to false,
                    "error" to mapOf("message" to data?.stringValue("error").orEmpty()),
                ),
            )
            else -> null
        } ?: return null
        return mapped.filterValues { it != null }
    }

    private fun stableUpdateIds(
        source: Map<String, Any?>,
        taskId: String,
        turnId: String,
        eventKey: String,
    ): Map<String, Any?> = LinkedHashMap(source).apply {
        when (stringValue("sessionUpdate")) {
            "agent_message_chunk", "agent_thought_chunk" -> if (stringValue("messageId").isNullOrBlank()) {
                put("messageId", stableId("message", taskId, turnId, stringValue("sessionUpdate").orEmpty()))
            }
            "tool_call", "tool_call_update" -> if (stringValue("toolCallId").isNullOrBlank()) {
                val signature = stringValue("title") ?: stringValue("kind") ?: "tool"
                val turnKey = "$taskId|$turnId|$signature"
                val queue = fallbackToolIds.computeIfAbsent(turnKey) { ArrayDeque() }
                val terminal = stringValue("status") in setOf("completed", "failed", "cancelled")
                val toolId = synchronized(queue) {
                    if (stringValue("sessionUpdate") == "tool_call") {
                        stableId("tool", taskId, turnId, eventKey).also(queue::addLast)
                    } else {
                        queue.firstOrNull() ?: stableId("tool", taskId, turnId, eventKey).also(queue::addLast)
                    }
                }
                put("toolCallId", toolId)
                if (terminal) {
                    synchronized(queue) { if (queue.firstOrNull() == toolId) queue.removeFirst() }
                    if (queue.isEmpty()) fallbackToolIds.remove(turnKey, queue)
                }
            }
        }
    }

    private fun taskSession(task: McCloudTask): Map<String, Any?> {
        val id = sessionId(task.id)
        return linkedMapOf(
            "sessionId" to id,
            "threadId" to id,
            "conversationId" to cloudConversationId(task.id),
            "title" to listOf(task.title, task.summary, task.content)
                .firstOrNull { !it.isNullOrBlank() },
            "updatedAt" to (task.completedAt ?: task.createdAt),
            "active" to (task.status == "processing"),
            "agentId" to MC_CLOUD_AGENT_ID,
            "agentName" to "MonkeyCode Cloud",
            "runtime" to "mccloud",
            "_meta" to mapOf(
                "cn.com.omnimind.agent" to mapOf(
                    "source" to "mccloud",
                    "runtime" to "mccloud",
                    "taskId" to task.id,
                    "status" to task.status,
                )
            ),
        ).filterValues { it != null }
    }

    private fun McCloudTaskChunk.asFrame(): Map<String, Any?> = linkedMapOf(
        "type" to event,
        "kind" to kind.takeIf(String::isNotBlank),
        "data" to data,
        "timestamp" to timestamp,
        "seq" to seq,
        "turn_seq" to turnSeq,
    ).filterValues { it != null }

    private fun userInputFrame(text: String): String {
        val payload = mapOf("content" to encodeBase64(text), "attachments" to emptyList<Any?>())
        return controlFrame("user-input", payload)
    }

    private fun controlFrame(type: String, payload: Map<String, Any?> = emptyMap()): String = gson.toJson(
        mapOf(
            "type" to type,
            "data" to encodeBase64(gson.toJson(payload)),
            "timestamp" to System.currentTimeMillis(),
        )
    )

    private fun promptText(args: Map<String, Any?>): String {
        args.stringValue("text")?.let { return it }
        args.stringValue("input")?.let { return it }
        val prompt = args["prompt"]
        if (prompt is String) return prompt
        return (prompt as? List<*>)
            .orEmpty()
            .mapNotNull { block ->
                when (block) {
                    is String -> block
                    is Map<*, *> -> block["text"]?.toString()
                        ?: (block["content"] as? Map<*, *>)?.get("text")?.toString()
                    else -> null
                }
            }
            .joinToString("\n")
    }

    private fun decodeFrameData(value: Any?): Map<String, Any?>? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to item }
        is String -> decodeBase64(value)?.let(::parseMap) ?: parseMap(value)
        else -> null
    }

    private fun parseMap(value: String): Map<String, Any?>? = runCatching {
        gson.fromJson<Map<String, Any?>>(value, mapType)
    }.getOrNull()

    private fun requireTaskId(args: Map<String, Any?>): String =
        cloudTaskId(args.stringValue("sessionId") ?: args.stringValue("threadId"))
            ?: throw IllegalArgumentException("McCloud sessionId is required")

    private data class CloudTurn(
        val sessionId: String,
        @Volatile var turnId: String,
        val finished: CompletableDeferred<String> = CompletableDeferred(),
        @Volatile var cancelling: Boolean = false,
    ) {
        fun response(completed: Boolean, deduplicated: Boolean = false): Map<String, Any?> = mapOf(
            "sessionId" to sessionId,
            "threadId" to sessionId,
            "promptId" to turnId,
            "turnId" to turnId,
            "completed" to completed,
            "deduplicated" to deduplicated,
        )
    }
}

internal fun sessionId(taskId: String): String = "$MC_CLOUD_SESSION_PREFIX$taskId"

internal fun cloudTaskId(sessionId: String?): String? = sessionId
    ?.takeIf { it.startsWith(MC_CLOUD_SESSION_PREFIX) }
    ?.removePrefix(MC_CLOUD_SESSION_PREFIX)
    ?.takeIf(String::isNotBlank)

internal fun cloudConversationId(taskId: String): Long {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("mccloud:$taskId".toByteArray(StandardCharsets.UTF_8))
    return -(ByteBuffer.wrap(bytes).long and Long.MAX_VALUE).coerceAtLeast(1L)
}

private fun stableId(namespace: String, vararg parts: String): String {
    val source = (listOf(namespace) + parts).joinToString("\u0000")
    return UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8)).toString()
}

private fun encodeBase64(value: String): String = Base64.getEncoder().encodeToString(
    value.toByteArray(StandardCharsets.UTF_8)
)

private fun decodeBase64(value: String?): String? = value?.let {
    runCatching {
        String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8)
    }.getOrNull()
}

private fun Map<String, Any?>.stringValue(key: String): String? =
    this[key]?.toString()?.trim()?.takeIf(String::isNotEmpty)

private fun Map<String, Any?>.longValue(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> =
    (this[key] as? Map<*, *>)?.entries?.associate { (nestedKey, value) ->
        nestedKey.toString() to value
    }.orEmpty()
