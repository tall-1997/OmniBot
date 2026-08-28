package cn.com.omnimind.baselib.mccloud

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McCloudTaskStreamTest {
    @Test
    fun urlUsesWebSocketSchemeEncodedTaskAndCookie() {
        val factory = RecordingWebSocketFactory()
        val stream = McCloudTaskStream(
            cookieProvider = McCloudCookieProvider { "session=value" },
            webSocketFactory = factory,
            scheduler = TestScheduler(),
        )

        stream.connect("task / 1", McCloudTaskStreamMode.CONTROL) {}

        val request = factory.requests.single()
        assertEquals("https", request.url.scheme)
        assertEquals("/api/v1/users/tasks/stream", request.url.encodedPath)
        assertEquals("task / 1", request.url.queryParameter("id"))
        assertEquals("control", request.url.queryParameter("mode"))
        assertEquals("session=value", request.header("Cookie"))
        stream.close()
    }

    @Test
    fun websocketUrlConvertsSecureHttpScheme() {
        val httpUrl = McCloudTaskStream.streamHttpUrl(
            "https://cloud.example",
            "task",
            McCloudTaskStreamMode.STREAM,
        )

        assertEquals(
            "wss://cloud.example/api/v1/users/tasks/stream?id=task&mode=stream",
            McCloudTaskStream.webSocketUrl(httpUrl),
        )
    }

    @Test
    fun replacingTaskConnectionClosesPreviousSocket() {
        val factory = RecordingWebSocketFactory()
        val stream = McCloudTaskStream(
            cookieProvider = McCloudCookieProvider { null },
            webSocketFactory = factory,
            scheduler = TestScheduler(),
        )

        stream.connect("same") {}
        stream.connect("same") {}

        assertTrue(factory.sockets.first().closed)
        assertFalse(factory.sockets.last().closed)
        stream.close()
    }

    @Test
    fun textAndNormalCloseAreDeliveredWithoutReconnect() {
        val factory = RecordingWebSocketFactory()
        val scheduler = TestScheduler()
        val events = mutableListOf<McCloudTaskStreamEvent>()
        val stream = McCloudTaskStream(
            cookieProvider = McCloudCookieProvider { null },
            webSocketFactory = factory,
            scheduler = scheduler,
        )
        stream.connect("task", onEvent = events::add)
        val socket = factory.sockets.single()

        socket.open()
        socket.listener.onMessage(socket, "hello")
        socket.listener.onClosed(socket, 1000, "complete")

        assertEquals(McCloudTaskStreamEvent.Text("hello"), events[0])
        assertEquals(McCloudTaskStreamEvent.Closed(1000, "complete"), events[1])
        assertTrue(scheduler.active.isEmpty())
        stream.close()
    }

    @Test
    fun failuresReconnectWithCappedExponentialDelay() {
        assertEquals(2_000L, McCloudTaskStream.retryDelayMillis(1))
        assertEquals(4_000L, McCloudTaskStream.retryDelayMillis(2))
        assertEquals(30_000L, McCloudTaskStream.retryDelayMillis(20))

        val factory = RecordingWebSocketFactory()
        val scheduler = TestScheduler()
        val stream = McCloudTaskStream(
            cookieProvider = McCloudCookieProvider { null },
            webSocketFactory = factory,
            scheduler = scheduler,
        )
        stream.connect("task") {}
        scheduler.cancelAll()

        factory.sockets.single().fail()

        assertEquals(2_000L, scheduler.active.single().delay)
        stream.close()
    }

    @Test
    fun taskEndedFrameClosesAndSuppressesReconnect() {
        val factory = RecordingWebSocketFactory()
        val scheduler = TestScheduler()
        val stream = McCloudTaskStream(
            cookieProvider = McCloudCookieProvider { null },
            webSocketFactory = factory,
            scheduler = scheduler,
        )
        stream.connect("task") {}
        val socket = factory.sockets.single()
        socket.open()

        socket.listener.onMessage(socket, "{\"type\":\"task-ended\"}")
        socket.listener.onFailure(socket, IllegalStateException("late"), null)

        assertTrue(socket.closed)
        assertTrue(scheduler.active.isEmpty())
        stream.close()
    }

    @Test
    fun constantsMatchTransportLimits() {
        assertEquals(64 * 1024 * 1024, McCloudTaskStream.MAX_MESSAGE_BYTES)
        assertEquals(20_000L, McCloudTaskStream.CONNECT_TIMEOUT_MILLIS)
    }
}

private class RecordingWebSocketFactory : WebSocket.Factory {
    val requests = mutableListOf<Request>()
    val sockets = mutableListOf<FakeWebSocket>()

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        requests += request
        return FakeWebSocket(request, listener).also(sockets::add)
    }
}

private class FakeWebSocket(
    private val originalRequest: Request,
    val listener: WebSocketListener,
) : WebSocket {
    var closed = false

    fun open() = listener.onOpen(
        this,
        Response.Builder().request(originalRequest).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build(),
    )

    fun fail() = listener.onFailure(this, IllegalStateException("failed"), null)
    override fun request(): Request = originalRequest
    override fun queueSize(): Long = 0
    override fun send(text: String): Boolean = !closed
    override fun send(bytes: ByteString): Boolean = !closed
    override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
    override fun cancel() { closed = true }
}

private class TestScheduler : McCloudTaskStream.Scheduler {
    data class Entry(val delay: Long, val block: () -> Unit, var cancelled: Boolean = false)
    val entries = mutableListOf<Entry>()
    val active get() = entries.filterNot { it.cancelled }

    override fun schedule(delayMillis: Long, block: () -> Unit): McCloudTaskStream.Scheduled {
        val entry = Entry(delayMillis, block)
        entries += entry
        return object : McCloudTaskStream.Scheduled {
            override fun cancel() { entry.cancelled = true }
        }
    }

    fun cancelAll() = entries.forEach { it.cancelled = true }
}
