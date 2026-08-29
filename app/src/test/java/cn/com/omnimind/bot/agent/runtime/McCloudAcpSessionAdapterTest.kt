package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.mccloud.McCloudPageInfo
import cn.com.omnimind.baselib.mccloud.McCloudTask
import cn.com.omnimind.baselib.mccloud.McCloudTaskChunk
import cn.com.omnimind.baselib.mccloud.McCloudTaskPage
import cn.com.omnimind.baselib.mccloud.McCloudTaskRounds
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class McCloudAcpSessionAdapterTest {
    @Test
    fun listAndLoadKeepCloudSessionAndConversationIdentityStable() = runBlocking {
        val gateway = FakeGateway()
        val events = mutableListOf<Map<String, Any?>>()
        val adapter = McCloudAcpSessionAdapter(gateway, events::add)

        val listed = adapter.listSessions(mapOf("limit" to 10))
        val session = (listed["sessions"] as List<*>).single() as Map<*, *>
        val loaded = adapter.loadSession(mapOf("sessionId" to "mccloud:task-1"))

        assertEquals("mccloud:task-1", session["sessionId"])
        assertEquals("mccloud", session["runtime"])
        assertEquals(session["conversationId"], loaded["conversationId"])
        assertEquals(cloudConversationId("task-1"), cloudConversationId("task-1"))
        assertNotEquals(cloudConversationId("task-1"), cloudConversationId("task-2"))
        assertEquals(true, events.single()["replay"])
        assertEquals("session/update", events.single()["method"])
        assertEquals(listOf("attach"), gateway.openedModes)
    }

    @Test
    fun roundsMapAcpUpdatesThroughOfficialSessionUpdateEnvelope() = runBlocking {
        val gateway = FakeGateway()
        val events = mutableListOf<Map<String, Any?>>()
        val adapter = McCloudAcpSessionAdapter(gateway, events::add)

        adapter.loadSession(mapOf("sessionId" to "mccloud:task-1"))

        val event = events.single()
        val params = event["params"] as Map<*, *>
        val update = params["update"] as Map<*, *>
        assertEquals("mccloud:task-1", params["sessionId"])
        assertEquals("agent_message_chunk", update["sessionUpdate"])
        assertTrue(update["messageId"].toString().isNotBlank())
        assertEquals(cloudConversationId("task-1"), event["conversationId"])
    }

    @Test
    fun promptWaitsForStreamAndCancelUsesSameTurnIdentity() = runBlocking {
        val gateway = FakeGateway()
        val events = mutableListOf<Map<String, Any?>>()
        val adapter = McCloudAcpSessionAdapter(gateway, events::add)
        val prompt = async {
            adapter.promptSession(
                mapOf(
                    "sessionId" to "mccloud:task-1",
                    "requestId" to "request-1",
                    "prompt" to listOf(mapOf("type" to "text", "text" to "hello")),
                )
            )
        }

        while (gateway.sent.isEmpty()) kotlinx.coroutines.yield()
        val cancel = async { adapter.cancelSession(mapOf("sessionId" to "mccloud:task-1")) }
        gateway.emitTerminal("task-1")
        val cancelled = cancel.await()
        val result = prompt.await()

        assertEquals(cancelled["turnId"], result["turnId"])
        assertEquals("end_turn", result["stopReason"])
        assertTrue(gateway.sent.first().contains("user-input"))
        assertTrue(gateway.sent.last().contains("user-cancel"))
        assertEquals(listOf("new"), gateway.openedModes)
    }

    @Test
    fun cleanCloseBeforeTerminalFailsPrompt() = runBlocking {
        val gateway = FakeGateway()
        val adapter = McCloudAcpSessionAdapter(gateway = gateway, publish = {})
        val prompt = async {
            runCatching {
                adapter.promptSession(
                    mapOf("sessionId" to "mccloud:task-1", "prompt" to "hello")
                )
            }.exceptionOrNull()
        }

        while (gateway.sent.isEmpty()) kotlinx.coroutines.yield()
        gateway.emit(mapOf("type" to "taskClosed", "taskId" to "task-1", "code" to 1000))

        assertTrue(prompt.await() is IllegalStateException)
    }

    @Test
    fun cancelTimesOutWithoutClaimingServerConfirmation() = runBlocking {
        val gateway = FakeGateway()
        val adapter = McCloudAcpSessionAdapter(
            gateway = gateway,
            publish = {},
            cancelTimeoutMs = 1,
        )
        val prompt = async {
            adapter.promptSession(mapOf("sessionId" to "mccloud:task-1", "prompt" to "hello"))
        }
        while (gateway.sent.isEmpty()) kotlinx.coroutines.yield()

        val cancelled = adapter.cancelSession(mapOf("sessionId" to "mccloud:task-1"))

        assertEquals(false, cancelled["ok"])
        assertEquals(false, cancelled["cancelled"])
        assertEquals(true, cancelled["timedOut"])
        assertEquals("cancelled", prompt.await()["stopReason"])
        assertTrue(gateway.closed)
    }

    @Test
    fun missingSequenceAndParallelToolsReceiveDistinctStableIds() = runBlocking {
        val gateway = FakeGateway(rounds = McCloudTaskRounds())
        val events = mutableListOf<Map<String, Any?>>()
        McCloudAcpSessionAdapter(gateway, events::add)
        gateway.emitAcp("task-1", mapOf("sessionUpdate" to "agent_message_chunk", "content" to mapOf("text" to "x")))
        gateway.emitAcp("task-1", mapOf("sessionUpdate" to "agent_message_chunk", "content" to mapOf("text" to "x")))
        gateway.emitAcp("task-1", mapOf("sessionUpdate" to "tool_call", "title" to "read"))
        gateway.emitAcp("task-1", mapOf("sessionUpdate" to "tool_call", "title" to "read"))
        gateway.emitAcp("task-1", mapOf("sessionUpdate" to "tool_call_update", "title" to "read", "status" to "completed"))
        gateway.emitAcp("task-1", mapOf("sessionUpdate" to "tool_call_update", "title" to "read", "status" to "completed"))

        assertNotEquals(events[0]["eventId"], events[1]["eventId"])
        val toolIds = events.drop(2).map { event ->
            (((event["params"] as Map<*, *>)["update"] as Map<*, *>)["toolCallId"])
        }
        assertNotEquals(toolIds[0], toolIds[1])
        assertEquals(toolIds[0], toolIds[2])
        assertEquals(toolIds[1], toolIds[3])
    }

    @Test
    fun cloudSessionsRemainAvailableWithoutAgentSessions() = runBlocking {
        val adapter = McCloudAcpSessionAdapter(FakeGateway(), publish = {})
        val cloud = adapter.listSessions(emptyMap())

        val merged = adapter.mergeSessions(base = null, cloud = cloud)

        assertEquals(1, (merged["sessions"] as List<*>).size)
        assertNull(merged["nextCursor"])
    }

    @Test
    fun onlyNamespacedSessionsAreClaimed() {
        val adapter = McCloudAcpSessionAdapter(
            gateway = FakeGateway(),
            publish = {},
        )

        assertTrue(adapter.ownsSession(mapOf("sessionId" to "mccloud:task-1")))
        assertFalse(adapter.ownsSession(mapOf("sessionId" to "local-session")))
    }

    private class FakeGateway(
        private val rounds: McCloudTaskRounds? = null,
    ) : McCloudAcpGateway {
        private var listener: ((Map<String, Any?>) -> Unit)? = null
        val sent = mutableListOf<String>()
        val openedModes = mutableListOf<String>()
        var closed = false
        private val update = mapOf(
            "update" to mapOf(
                "sessionUpdate" to "agent_message_chunk",
                "content" to mapOf("type" to "text", "text" to "hello"),
            )
        )
        private val chunk = McCloudTaskChunk(
            event = "task-running",
            kind = "acp_event",
            data = Base64.getEncoder().encodeToString(
                Gson().toJson(update).toByteArray(StandardCharsets.UTF_8)
            ),
            timestamp = 100,
            seq = 7,
            turnSeq = 3,
        )

        override suspend fun listTasks(page: Int, size: Int) = McCloudTaskPage(
            tasks = listOf(task()),
            pageInfo = McCloudPageInfo(page = 1, size = size),
        )

        override suspend fun getTaskDetail(taskId: String) = task()

        override suspend fun getTaskRounds(taskId: String, limit: Int) =
            rounds ?: McCloudTaskRounds(chunks = listOf(chunk))

        override fun openTaskStream(taskId: String, mode: String): String {
            openedModes += mode
            emit(mapOf("type" to "taskConnected", "taskId" to taskId))
            return taskId
        }

        override fun sendTaskStreamMessage(taskId: String, data: String): Boolean {
            sent += data
            return true
        }

        override fun closeTaskStream(taskId: String) {
            closed = true
        }

        override fun subscribe(listener: (Map<String, Any?>) -> Unit): () -> Unit {
            this.listener = listener
            return { this.listener = null }
        }

        fun emit(event: Map<String, Any?>) {
            listener?.invoke(event)
        }

        fun emitTerminal(taskId: String) {
            emit(mapOf("type" to "taskMessage", "taskId" to taskId, "data" to "{\"type\":\"task-ended\",\"turn_seq\":4}"))
        }

        fun emitAcp(taskId: String, update: Map<String, Any?>) {
            val data = Base64.getEncoder().encodeToString(
                Gson().toJson(mapOf("update" to update)).toByteArray(StandardCharsets.UTF_8)
            )
            emit(
                mapOf(
                    "type" to "taskMessage",
                    "taskId" to taskId,
                    "data" to Gson().toJson(mapOf("type" to "task-running", "kind" to "acp_event", "data" to data)),
                )
            )
        }

        private fun task() = McCloudTask(
            id = "task-1",
            title = "Cloud task",
            status = "processing",
            createdAt = 50,
        )
    }
}
