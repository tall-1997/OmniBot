package cn.com.omnimind.baselib.mccloud

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class McCloudTaskStreamManager(
    endpoints: McCloudEndpoints,
    private val cookieJar: McCloudCookieJar,
    private val client: OkHttpClient,
    private val session: McCloudSessionManager,
    private val emit: (Map<String, Any?>) -> Unit,
    allowInsecureLoopback: Boolean = false,
) {
    private val baseUrl = McCloudEndpoint.normalize(
        endpoints.monkeyCode,
        "monkeyCodeBaseUrl",
        allowInsecureLoopback,
    ).toHttpUrl()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val generations = ConcurrentHashMap<String, Long>()
    private val reconnects = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mc-cloud-task-reconnect").apply { isDaemon = true }
    }

    fun open(taskId: String, mode: String = "stream"): String {
        requireResourceId(taskId)
        require(mode == "stream" || mode == "control") { "任务流模式无效" }
        close(taskId)
        val generation = System.nanoTime()
        generations[taskId] = generation
        connect(taskId, mode, generation, 0)
        return taskId
    }

    fun send(taskId: String, text: String): Boolean = sockets[taskId]?.send(text) == true

    fun close(taskId: String) {
        generations.remove(taskId)
        reconnects.remove(taskId)?.cancel(false)
        sockets.remove(taskId)?.close(1000, "client closed")
    }

    fun closeAll() {
        generations.keys.toList().forEach(::close)
    }

    fun shutdown() {
        closeAll()
        scheduler.shutdownNow()
    }

    private fun connect(taskId: String, mode: String, generation: Long, attempt: Int) {
        if (generations[taskId] != generation) return
        val httpUrl = McCloudEndpoint.resolve(baseUrl.toString(), "/api/v1/users/tasks/stream").newBuilder()
            .addQueryParameter("id", taskId)
            .addQueryParameter("mode", mode)
            .build()
        val wsUrl = httpUrl.newBuilder().scheme(if (httpUrl.isHttps) "wss" else "ws").build()
        val request = Request.Builder().url(wsUrl).apply {
            cookieJar.cookieHeader(McCloudDomain.MONKEY_CODE).takeIf(String::isNotEmpty)?.let { header("Cookie", it) }
        }.build()
        var opened = false
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generations[taskId] != generation) {
                    webSocket.close(1000, "superseded")
                    return
                }
                opened = true
                sockets[taskId] = webSocket
                emit(event("taskConnected", taskId, mapOf("mode" to mode)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generations[taskId] != generation) return
                emit(event("taskMessage", taskId, mapOf("data" to text)))
                if (isTaskEnded(text)) {
                    generations.remove(taskId, generation)
                    webSocket.close(1000, "task ended")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(taskId, webSocket)
                if (code == 1000) {
                    generations.remove(taskId, generation)
                    emit(event("taskClosed", taskId, mapOf("code" to code)))
                } else {
                    reconnect(taskId, mode, generation, if (opened) 0 else attempt)
                }
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                sockets.remove(taskId, webSocket)
                if (generations[taskId] != generation) return
                if (response?.code == 401) {
                    generations.remove(taskId, generation)
                    session.handleUnauthorized()
                    return
                }
                emit(event("taskDisconnected", taskId, mapOf("statusCode" to response?.code)))
                reconnect(taskId, mode, generation, if (opened) 0 else attempt)
            }
        })
        sockets[taskId] = socket
    }

    private fun reconnect(taskId: String, mode: String, generation: Long, attempt: Int) {
        if (generations[taskId] != generation) return
        val delayMillis = taskReconnectDelayMillis(attempt)
        emit(event("taskReconnecting", taskId, mapOf("delayMs" to delayMillis)))
        reconnects.remove(taskId)?.cancel(false)
        reconnects[taskId] = scheduler.schedule(
            {
                reconnects.remove(taskId)
                connect(taskId, mode, generation, attempt + 1)
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun event(type: String, taskId: String, extra: Map<String, Any?>) =
        linkedMapOf<String, Any?>("type" to type, "taskId" to taskId).apply { putAll(extra) }

    private fun isTaskEnded(text: String): Boolean = runCatching {
        Gson().fromJson(text, JsonObject::class.java).get("type")?.asString == "task-ended"
    }.getOrDefault(false)
}

data class McCloudUploadedAttachment(val url: String, val filename: String)
private data class PresignResponse(
    @com.google.gson.annotations.SerializedName("upload_url") val uploadUrl: String = "",
    @com.google.gson.annotations.SerializedName("access_url") val accessUrl: String = "",
)

class McCloudFileManager(
    endpoints: McCloudEndpoints,
    private val cookieJar: McCloudCookieJar,
    private val client: OkHttpClient,
    private val session: McCloudSessionManager,
    private val emit: (Map<String, Any?>) -> Unit,
    private val gson: Gson = Gson(),
    allowInsecureLoopback: Boolean = false,
) {
    private val baseUrl = McCloudEndpoint.normalize(
        endpoints.monkeyCode,
        "monkeyCodeBaseUrl",
        allowInsecureLoopback,
    )
    private val calls = ConcurrentHashMap<String, Call>()

    suspend fun uploadAttachment(operationId: String, source: File): McCloudUploadedAttachment {
        requireOperation(operationId, source)
        require(source.length() <= 4L * 1024 * 1024) { "附件过大" }
        val presign = requestJson<PresignResponse>(
            Request.Builder().url(McCloudEndpoint.resolve(baseUrl, "/api/v1/uploader/presign"))
                .post(gson.toJson(mapOf("filename" to source.name)).toRequestBody(JSON_MEDIA_TYPE))
                .cloudCookies().build(),
        )
        require(presign.uploadUrl.isNotBlank() && presign.accessUrl.isNotBlank()) { "预签名响应缺少 URL" }
        execute(operationId, Request.Builder().url(presign.uploadUrl)
            .put(progressBody(operationId, source)).build())
        return McCloudUploadedAttachment(presign.accessUrl, source.name)
    }

    suspend fun uploadVmFile(operationId: String, vmId: String, path: String, source: File) {
        requireOperation(operationId, source)
        requireResourceId(vmId)
        require(path.startsWith('/') && path.substringAfterLast('/').isNotBlank()) { "目标路径必须是文件绝对路径" }
        require(source.length() <= 12L * 1024 * 1024) { "文件过大" }
        val url = McCloudEndpoint.resolve(baseUrl, "/api/v1/users/files/upload").newBuilder()
            .addQueryParameter("id", vmId).addQueryParameter("path", path).build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", source.name, progressBody(operationId, source)).build()
        execute(operationId, Request.Builder().url(url).post(body).cloudCookies().build())
    }

    suspend fun downloadVmFile(
        operationId: String,
        vmId: String,
        path: String,
        filename: String,
        destination: File,
    ): Long = withContext(Dispatchers.IO) {
        require(operationId.isNotBlank() && operationId.length <= 64) { "操作 ID 无效" }
        requireResourceId(vmId)
        require(path.startsWith('/')) { "目标路径必须是绝对路径" }
        require(filename.isNotBlank()) { "下载文件名不能为空" }
        val parent = destination.parentFile ?: throw McCloudStorageUnavailableException()
        if (!parent.isDirectory && !parent.mkdirs()) throw McCloudStorageUnavailableException()
        val partial = try {
            Files.createTempFile(parent.toPath(), ".${destination.name}.", ".mcpart").toFile()
        } catch (error: IOException) {
            throw McCloudStorageUnavailableException(error)
        }
        val url = McCloudEndpoint.resolve(baseUrl, "/api/v1/users/files/download").newBuilder()
            .addQueryParameter("id", vmId).addQueryParameter("path", path)
            .addQueryParameter("filename", filename).build()
        try {
            val response = executeRaw(operationId, Request.Builder().url(url).cloudCookies().build())
            response.use {
                handleUnauthorized(it)
                if (!it.isSuccessful) throw McCloudApiException(it.code, message = "下载失败（${it.code}）")
                val total = it.body?.contentLength()?.takeIf { length -> length >= 0 }
                var written = 0L
                partial.outputStream().buffered().use { output ->
                    val input = it.body?.byteStream() ?: throw McCloudApiException(message = "下载响应为空")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        emit(progressEvent(operationId, "downloadProgress", written, total))
                    }
                }
                moveCompletedDownload(partial, destination)
                emit(progressEvent(operationId, "downloadCompleted", written, total))
                written
            }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            calls.remove(operationId)
        }
    }

    fun cancel(operationId: String): Boolean = calls.remove(operationId)?.let {
        it.cancel()
        emit(mapOf("type" to "transferCanceled", "operationId" to operationId))
        true
    } ?: false

    fun cancelAll() = calls.keys.toList().forEach(::cancel)

    fun shutdown() = cancelAll()

    private suspend inline fun <reified T> requestJson(request: Request): T {
        val operationId = "metadata-${System.nanoTime()}"
        try {
            val response = executeRaw(operationId, request)
            response.use {
                val text = it.body?.string().orEmpty()
                handleUnauthorized(it)
                if (!it.isSuccessful) throw McCloudApiException(it.code, message = "请求失败（${it.code}）")
                val root = gson.fromJson(text, com.google.gson.JsonObject::class.java)
                val payload = root.get("data") ?: root
                return gson.fromJson(payload, T::class.java)
            }
        } finally {
            calls.remove(operationId)
        }
    }

    private suspend fun execute(operationId: String, request: Request) {
        try {
            executeRaw(operationId, request).use {
                val text = it.body?.string().orEmpty()
                handleUnauthorized(it)
                if (!it.isSuccessful) throw McCloudApiException(it.code, message = "上传失败（${it.code}）")
                if (text.isNotBlank() && request.url.host == baseUrl.toHttpUrl().host) {
                    val json = runCatching { gson.fromJson(text, com.google.gson.JsonObject::class.java) }.getOrNull()
                    val code = json?.get("code")?.runCatching { asInt }?.getOrNull()
                    if (code != null && code != 0) throw McCloudApiException(it.code, code, cleanMcCloudMessage(json.get("message")?.asString))
                }
            }
            emit(mapOf("type" to "uploadCompleted", "operationId" to operationId))
        } finally {
            calls.remove(operationId)
        }
    }

    private suspend fun executeRaw(operationId: String, request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        check(calls.putIfAbsent(operationId, call) == null) { "操作 ID 已在使用" }
        continuation.invokeOnCancellation { calls.remove(operationId, call); call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                calls.remove(operationId, call)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private fun Request.Builder.cloudCookies() = apply {
        cookieJar.cookieHeader(McCloudDomain.MONKEY_CODE).takeIf(String::isNotEmpty)?.let { header("Cookie", it) }
    }

    private fun handleUnauthorized(response: Response) {
        if (response.code != 401) return
        session.handleUnauthorized()
        throw McCloudApiException(statusCode = 401, message = "登录已过期，请重新登录")
    }

    private fun moveCompletedDownload(partial: File, destination: File) {
        try {
            Files.move(
                partial.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun progressBody(operationId: String, source: File) = object : okhttp3.RequestBody() {
        private val delegate = source.asRequestBody(null)
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val forwarding = object : ForwardingSink(sink) {
                var sent = 0L
                override fun write(sourceBuffer: Buffer, byteCount: Long) {
                    super.write(sourceBuffer, byteCount)
                    sent += byteCount
                    emit(progressEvent(operationId, "uploadProgress", sent, contentLength()))
                }
            }
            val buffered = forwarding.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }

    private fun requireOperation(operationId: String, source: File) {
        require(operationId.isNotBlank() && operationId.length <= 64) { "操作 ID 无效" }
        require(source.isFile && source.length() > 0) { "本地文件无效" }
    }

    private fun progressEvent(id: String, type: String, transferred: Long, total: Long?) = mapOf(
        "type" to type, "operationId" to id, "transferred" to transferred, "total" to total,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}

private fun requireResourceId(value: String) {
    require(value.isNotBlank() && value.length <= 128 && value.none { it == '/' || it == '?' || it == '#' }) {
        "资源 ID 无效"
    }
}

internal fun taskReconnectDelayMillis(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)
