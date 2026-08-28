package cn.com.omnimind.baselib.mccloud

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface McCloudCookieProvider {
    fun cookieHeader(url: HttpUrl): String?
}

sealed class McCloudTaskStreamEvent {
    data class Text(val value: String) : McCloudTaskStreamEvent()
    data class Closed(val code: Int?, val reason: String, val cause: Throwable? = null) : McCloudTaskStreamEvent()
}

enum class McCloudTaskStreamMode(val wireValue: String) {
    STREAM("stream"),
    CONTROL("control"),
}

class McCloudTaskStream(
    endpoints: McCloudEndpoints = McCloudEndpoints(),
    private val cookieProvider: McCloudCookieProvider,
    private val webSocketFactory: WebSocket.Factory = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
    private val scheduler: Scheduler = ExecutorScheduler(),
    allowInsecureLoopback: Boolean = false,
) : AutoCloseable {
    private val baseUrl = McCloudEndpoint.normalize(
        endpoints.monkeyCode,
        "monkeyCodeBaseUrl",
        allowInsecureLoopback,
    )
    private val connections = ConcurrentHashMap<String, Connection>()

    fun connect(
        taskId: String,
        mode: McCloudTaskStreamMode = McCloudTaskStreamMode.STREAM,
        onEvent: (McCloudTaskStreamEvent) -> Unit,
    ): Handle {
        require(taskId.isNotBlank()) { "taskId is required" }
        val connection = Connection(taskId, mode, onEvent)
        connections.put(taskId, connection)?.stop()
        connection.dial()
        return connection
    }

    fun disconnect(taskId: String) {
        connections.remove(taskId)?.stop()
    }

    override fun close() {
        connections.values.toList().forEach(Connection::stop)
        connections.clear()
        scheduler.close()
    }

    interface Handle : AutoCloseable {
        fun send(text: String): Boolean
        override fun close()
    }

    interface Scheduled {
        fun cancel()
    }

    interface Scheduler : AutoCloseable {
        fun schedule(delayMillis: Long, block: () -> Unit): Scheduled
        override fun close() = Unit
    }

    private inner class Connection(
        private val taskId: String,
        private val mode: McCloudTaskStreamMode,
        private val onEvent: (McCloudTaskStreamEvent) -> Unit,
    ) : Handle {
        private val stopped = AtomicBoolean(false)
        private var socket: WebSocket? = null
        private var pending: Scheduled? = null
        private var failures = 0
        private var terminal = false

        @Synchronized
        fun dial() {
            if (stopped.get() || terminal || connections[taskId] !== this) return
            val httpUrl = streamHttpUrl(baseUrl, taskId, mode)
            val request = Request.Builder().url(webSocketUrl(httpUrl)).apply {
                cookieProvider.cookieHeader(httpUrl)?.takeIf(String::isNotBlank)?.let { header("Cookie", it) }
            }.build()
            val listener = Listener()
            socket = webSocketFactory.newWebSocket(request, listener)
            pending = scheduler.schedule(CONNECT_TIMEOUT_MILLIS) {
                listener.fail(IllegalStateException("连接云端任务流超时"))
            }
        }

        @Synchronized
        override fun send(text: String): Boolean = !stopped.get() && socket?.send(text) == true

        override fun close() {
            connections.remove(taskId, this)
            stop()
        }

        @Synchronized
        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            pending?.cancel()
            pending = null
            socket?.close(NORMAL_CLOSE_CODE, "client closed")
            socket = null
        }

        @Synchronized
        private fun opened() {
            pending?.cancel()
            pending = null
            failures = 0
        }

        @Synchronized
        private fun ended() {
            terminal = true
            pending?.cancel()
            pending = null
            socket?.close(NORMAL_CLOSE_CODE, "task ended")
        }

        @Synchronized
        private fun disconnected(event: McCloudTaskStreamEvent.Closed, retry: Boolean) {
            pending?.cancel()
            pending = null
            socket = null
            if (stopped.get() || connections[taskId] !== this) return
            onEvent(event)
            if (terminal) {
                connections.remove(taskId, this)
                return
            }
            if (!retry) {
                terminal = true
                connections.remove(taskId, this)
                return
            }
            failures += 1
            val delay = retryDelayMillis(failures)
            pending = scheduler.schedule(delay, ::dial)
        }

        private inner class Listener : WebSocketListener() {
            private val finished = AtomicBoolean(false)

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (finished.get()) {
                    webSocket.cancel()
                    return
                }
                opened()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                    webSocket.close(MESSAGE_TOO_BIG_CODE, "message exceeds 64 MiB")
                    fail(IllegalArgumentException("云端任务消息超过 64 MiB"))
                    return
                }
                onEvent(McCloudTaskStreamEvent.Text(text))
                if (isTaskEnded(text)) ended()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > MAX_MESSAGE_BYTES) {
                    webSocket.close(MESSAGE_TOO_BIG_CODE, "message exceeds 64 MiB")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finished.compareAndSet(false, true)) return
                disconnected(McCloudTaskStreamEvent.Closed(code, reason), code != NORMAL_CLOSE_CODE)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(t)

            fun fail(error: Throwable) {
                if (!finished.compareAndSet(false, true)) return
                socket?.cancel()
                disconnected(McCloudTaskStreamEvent.Closed(null, error.message.orEmpty(), error), retry = true)
            }
        }
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 64 * 1024 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val CONNECT_TIMEOUT_SECONDS = 20L
        private const val NORMAL_CLOSE_CODE = 1000
        private const val MESSAGE_TOO_BIG_CODE = 1009
        private const val INITIAL_RETRY_MILLIS = 2_000L
        private const val MAX_RETRY_MILLIS = 30_000L

        fun retryDelayMillis(failureCount: Int): Long {
            if (failureCount <= 1) return INITIAL_RETRY_MILLIS
            val shift = (failureCount - 1).coerceAtMost(30)
            return (INITIAL_RETRY_MILLIS * (1L shl shift)).coerceAtMost(MAX_RETRY_MILLIS)
        }

        internal fun streamHttpUrl(baseUrl: String, taskId: String, mode: McCloudTaskStreamMode): HttpUrl =
            McCloudEndpoint.resolve(baseUrl, "/api/v1/users/tasks/stream").newBuilder()
                .addQueryParameter("id", taskId)
                .addQueryParameter("mode", mode.wireValue)
                .build()

        internal fun webSocketUrl(httpUrl: HttpUrl): String = when (httpUrl.scheme) {
            "https" -> httpUrl.toString().replaceFirst("https://", "wss://")
            else -> httpUrl.toString().replaceFirst("http://", "ws://")
        }

        internal fun isTaskEnded(text: String): Boolean =
            TASK_ENDED.containsMatchIn(text)

        private val TASK_ENDED = Regex("\\\"(?:type|event)\\\"\\s*:\\s*\\\"task-ended\\\"")
    }
}

private class ExecutorScheduler : McCloudTaskStream.Scheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mc-cloud-task-stream").apply { isDaemon = true }
    }

    override fun schedule(delayMillis: Long, block: () -> Unit): McCloudTaskStream.Scheduled {
        val future: ScheduledFuture<*> = executor.schedule(block, delayMillis, TimeUnit.MILLISECONDS)
        return object : McCloudTaskStream.Scheduled {
            override fun cancel() {
                future.cancel(false)
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
