package cn.com.omnimind.baselib.mccloud

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class McCloudPresignedUpload(val uploadUrl: String, val accessUrl: String)
data class McCloudDownloadProgress(val written: Long, val total: Long?)

class McCloudAtomicFileManager(
    endpoints: McCloudEndpoints = McCloudEndpoints(),
    private val cookieProvider: McCloudCookieProvider,
    private val callFactory: Call.Factory,
    private val gson: Gson = Gson(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    allowInsecureLoopback: Boolean = false,
) {
    private val baseUrl = McCloudEndpoint.normalize(
        endpoints.monkeyCode,
        "monkeyCodeBaseUrl",
        allowInsecureLoopback,
    )
    private val downloads = ConcurrentHashMap<String, Download>()

    suspend fun uploadAttachment(file: File): String = withContext(ioDispatcher) {
        validateFile(file)
        val presign = requestPresign(file.name)
        val uploadUrl = presign.uploadUrl.toHttpUrlOrNull()
            ?: throw McCloudApiException(message = "预签名上传地址无效")
        execute(
            Request.Builder()
                .url(uploadUrl)
                .put(FileRequestBody(file))
                .build(),
            includeCookie = false,
        ).use { response ->
            if (!response.isSuccessful) throw McCloudApiException(response.code, message = "上传附件失败（${response.code}）")
        }
        presign.accessUrl
    }

    suspend fun uploadVmFile(vmId: String, remotePath: String, file: File): Unit = withContext(ioDispatcher) {
        require(vmId.isNotBlank()) { "vmId is required" }
        require(remotePath.startsWith('/') && remotePath.substringAfterLast('/').isNotBlank()) {
            "remotePath must be an absolute file path"
        }
        validateFile(file)
        val url = endpoint("/api/v1/users/files/upload").newBuilder()
            .addQueryParameter("id", vmId)
            .addQueryParameter("path", remotePath)
            .build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", remotePath.substringAfterLast('/'), FileRequestBody(file))
            .build()
        execute(Request.Builder().url(url).post(body).build()).use { response ->
            parseSuccess(response.code, response.isSuccessful, response.body?.string().orEmpty())
        }
        Unit
    }

    suspend fun downloadVmFile(
        downloadId: String,
        vmId: String,
        remotePath: String,
        filename: String,
        destination: File,
        onProgress: (McCloudDownloadProgress) -> Unit = {},
    ): Long = withContext(ioDispatcher) {
        require(downloadId.isNotBlank() && downloadId.length <= 64) { "downloadId is invalid" }
        require(vmId.isNotBlank()) { "vmId is required" }
        require(remotePath.startsWith('/')) { "remotePath must be absolute" }
        require(filename.isNotBlank()) { "filename is required" }
        destination.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "destination directory is unavailable" } }

        val download = Download()
        check(downloads.putIfAbsent(downloadId, download) == null) { "downloadId is already active" }
        val temporary = File(destination.parentFile ?: File("."), ".${destination.name}.${UUID.randomUUID()}.part")
        try {
            val url = endpoint("/api/v1/users/files/download").newBuilder()
                .addQueryParameter("id", vmId)
                .addQueryParameter("path", remotePath)
                .addQueryParameter("filename", filename)
                .build()
            val call = newCall(Request.Builder().url(url).get().build())
            download.call = call
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    parseSuccess(response.code, false, response.body?.string().orEmpty())
                }
                val body = response.body ?: throw McCloudApiException(response.code, message = "下载响应缺少内容")
                val total = body.contentLength().takeIf { it >= 0 }
                val source = body.source()
                source.timeout().timeout(STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                temporary.sink().buffer().use { sink ->
                    var written = 0L
                    var lastProgressAt = clockMillis()
                    onProgress(McCloudDownloadProgress(0, total))
                    while (true) {
                        if (download.cancelled.get()) throw McCloudDownloadCancelledException()
                        val read = source.read(sink.buffer, BUFFER_SIZE_BYTES.toLong())
                        if (read == -1L) break
                        sink.emitCompleteSegments()
                        written += read
                        val now = clockMillis()
                        if (now >= lastProgressAt + PROGRESS_INTERVAL_MILLIS) {
                            onProgress(McCloudDownloadProgress(written, total))
                            lastProgressAt = now
                        }
                    }
                    sink.flush()
                    if (download.cancelled.get()) throw McCloudDownloadCancelledException()
                    atomicMove(temporary, destination)
                    onProgress(McCloudDownloadProgress(written, total))
                    written
                }
            }
        } catch (error: IOException) {
            if (download.cancelled.get()) throw McCloudDownloadCancelledException(error)
            throw McCloudApiException(message = "文件下载失败", cause = error)
        } finally {
            downloads.remove(downloadId, download)
            if (temporary.exists()) temporary.delete()
        }
    }

    fun cancel(downloadId: String): Boolean {
        val download = downloads[downloadId] ?: return false
        download.cancelled.set(true)
        download.call?.cancel()
        return true
    }

    private fun requestPresign(filename: String): McCloudPresignedUpload {
        val body = gson.toJson(mapOf("filename" to filename)).toRequestBody(JSON_MEDIA_TYPE)
        execute(Request.Builder().url(endpoint("/api/v1/uploader/presign")).post(body).build()).use { response ->
            val data = parseSuccess(response.code, response.isSuccessful, response.body?.string().orEmpty())
            val uploadUrl = data.get("upload_url")?.asString.orEmpty()
            val accessUrl = data.get("access_url")?.asString.orEmpty()
            if (uploadUrl.isBlank() || accessUrl.isBlank()) {
                throw McCloudApiException(response.code, message = "预签名响应缺少上传或访问地址")
            }
            return McCloudPresignedUpload(uploadUrl, accessUrl)
        }
    }

    private fun parseSuccess(status: Int, successful: Boolean, text: String): JsonObject {
        val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
        val code = root?.get("code")?.let { runCatching { it.asInt }.getOrNull() }
        if (!successful || code != null && code != 0) {
            val message = root?.get("message")?.let { runCatching { it.asString }.getOrNull() }
            throw McCloudApiException(status, code, cleanMcCloudMessage(message ?: "请求失败（$status）"))
        }
        return root?.getAsJsonObject("data") ?: JsonObject()
    }

    private fun execute(request: Request, includeCookie: Boolean = true) = newCall(request, includeCookie).execute()

    private fun newCall(request: Request, includeCookie: Boolean = true): Call {
        val authenticated = if (includeCookie) request.newBuilder().apply {
            cookieProvider.cookieHeader(request.url)?.takeIf(String::isNotBlank)?.let { header("Cookie", it) }
        }.build() else request
        return callFactory.newCall(authenticated)
    }

    private fun endpoint(path: String): HttpUrl = McCloudEndpoint.resolve(baseUrl, path)

    private fun validateFile(file: File) {
        require(file.isFile) { "file is required" }
        require(file.length() in 1..MAX_FILE_BYTES) { "file size must be between 1 byte and 64 MiB" }
    }

    private fun atomicMove(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private class Download {
        val cancelled = AtomicBoolean(false)
        @Volatile var call: Call? = null
    }

    private class FileRequestBody(private val file: File) : RequestBody() {
        override fun contentType() = null
        override fun contentLength(): Long = file.length()
        override fun writeTo(sink: BufferedSink) {
            FileInputStream(file).use { input -> sink.writeAll(input.source()) }
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        const val STALL_TIMEOUT_SECONDS = 120L
        const val PROGRESS_INTERVAL_MILLIS = 150L
        private const val BUFFER_SIZE_BYTES = 64 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class McCloudDownloadCancelledException(cause: Throwable? = null) : IOException("文件下载已取消", cause)
