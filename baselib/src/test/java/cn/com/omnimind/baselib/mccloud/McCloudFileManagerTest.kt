package cn.com.omnimind.baselib.mccloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class McCloudAtomicFileManagerTest {
    @Test
    fun attachmentUsesAuthenticatedPresignThenBarePut() = withTempDir { dir -> runBlocking {
        val file = File(dir, "note.txt").apply { writeText("content") }
        val calls = QueueCallFactory(
            Stub(200, """{"code":0,"data":{"upload_url":"https://objects.example/upload","access_url":"https://cdn.example/note"}}"""),
            Stub(200, ""),
        )
        val manager = manager(calls)

        val accessUrl = manager.uploadAttachment(file)

        assertEquals("https://cdn.example/note", accessUrl)
        assertEquals("session=value", calls.requests[0].header("Cookie"))
        assertEquals("PUT", calls.requests[1].method)
        assertNull(calls.requests[1].header("Cookie"))
        assertNull(calls.requests[1].header("Content-Type"))
        assertEquals("content", requestBody(calls.requests[1]))
    } }

    @Test
    fun vmUploadIsMultipartAndCarriesCookie() = withTempDir { dir -> runBlocking {
        val file = File(dir, "local.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val calls = QueueCallFactory(Stub(200, """{"code":0,"data":{}}"""))
        val manager = manager(calls)

        manager.uploadVmFile("vm 1", "/workspace/remote.bin", file)

        val request = calls.requests.single()
        assertEquals("/api/v1/users/files/upload", request.url.encodedPath)
        assertEquals("vm 1", request.url.queryParameter("id"))
        assertEquals("/workspace/remote.bin", request.url.queryParameter("path"))
        assertTrue(request.body!!.contentType().toString().startsWith("multipart/form-data"))
        assertTrue(requestBody(request).contains("filename=\"remote.bin\""))
        assertEquals("session=value", request.header("Cookie"))
    } }

    @Test
    fun successfulDownloadStreamsThenMovesIntoDestination() = withTempDir { dir -> runBlocking {
        val destination = File(dir, "result.zip")
        val calls = QueueCallFactory(Stub(200, "payload"))
        val progress = mutableListOf<McCloudDownloadProgress>()

        val bytes = manager(calls).downloadVmFile(
            "download", "vm", "/workspace/output", "output.zip", destination, progress::add,
        )

        assertEquals(7L, bytes)
        assertEquals("payload", destination.readText())
        assertEquals(McCloudDownloadProgress(0, 7), progress.first())
        assertEquals(McCloudDownloadProgress(7, 7), progress.last())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    } }

    @Test
    fun cancellationKeepsExistingDestinationAndCleansTemporaryFile() = withTempDir { dir -> runBlocking {
        val destination = File(dir, "result.txt").apply { writeText("old") }
        val calls = QueueCallFactory(Stub(200, "new"))
        val manager = manager(calls)

        val error = runCatching {
            manager.downloadVmFile("cancel-me", "vm", "/workspace/a", "a", destination) {
                manager.cancel("cancel-me")
            }
        }.exceptionOrNull()

        assertTrue(error is McCloudDownloadCancelledException)
        assertEquals("old", destination.readText())
        assertTrue(calls.calls.single().isCanceled())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertFalse(manager.cancel("cancel-me"))
    } }

    @Test
    fun fileLimitAndTransferConstantsMatchSpecification() = withTempDir { dir -> runBlocking {
        assertEquals(64L * 1024 * 1024, McCloudAtomicFileManager.MAX_FILE_BYTES)
        assertEquals(120L, McCloudAtomicFileManager.STALL_TIMEOUT_SECONDS)
        assertEquals(150L, McCloudAtomicFileManager.PROGRESS_INTERVAL_MILLIS)
        val oversized = File(dir, "large")
        RandomAccessFile(oversized, "rw").use {
            it.setLength(McCloudAtomicFileManager.MAX_FILE_BYTES + 1)
        }
        val error = runCatching { manager(QueueCallFactory()).uploadAttachment(oversized) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    } }

    private fun manager(calls: QueueCallFactory) = McCloudAtomicFileManager(
        endpoints = McCloudEndpoints(monkeyCode = "https://cloud.example"),
        cookieProvider = McCloudCookieProvider { "session=value" },
        callFactory = calls,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun requestBody(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("mc-cloud-files-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}

private data class Stub(val code: Int, val body: String)

private class QueueCallFactory(vararg stubs: Stub) : Call.Factory {
    private val queue = ArrayDeque(stubs.toList())
    val requests = mutableListOf<Request>()
    val calls = mutableListOf<StubFileCall>()

    override fun newCall(request: Request): Call {
        requests += request
        return StubFileCall(request, checkNotNull(queue.removeFirstOrNull())).also(calls::add)
    }
}

private class StubFileCall(private val original: Request, private val stub: Stub) : Call {
    private var executed = false
    private var cancelled = false
    override fun request(): Request = original
    override fun execute(): Response {
        executed = true
        return Response.Builder().request(original).protocol(Protocol.HTTP_1_1).code(stub.code)
            .message("stub").body(stub.body.toResponseBody("application/octet-stream".toMediaType())).build()
    }
    override fun enqueue(responseCallback: Callback) = error("unused")
    override fun cancel() { cancelled = true }
    override fun isExecuted(): Boolean = executed
    override fun isCanceled(): Boolean = cancelled
    override fun clone(): Call = StubFileCall(original, stub)
    override fun timeout(): Timeout = Timeout.NONE
}
