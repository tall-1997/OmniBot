package cn.com.omnimind.baselib.mccloud

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.lang.reflect.Type

interface McCloudRemote {
    suspend fun <T> request(
        domain: McCloudDomain,
        path: String,
        method: String = "GET",
        body: Any? = null,
        query: Map<String, Any?> = emptyMap(),
        type: Type,
    ): T

    fun resetUnauthorizedState() = Unit
}

class McCloudApiClient(
    endpoints: McCloudEndpoints = McCloudEndpoints(),
    private val cookieJar: McCloudCookieJar,
    private val callFactory: Call.Factory = OkHttpClient.Builder().cookieJar(cookieJar).build(),
    private val gson: Gson = Gson(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onUnauthorized: (() -> Unit)? = null,
    allowInsecureLoopback: Boolean = false,
) : McCloudRemote {
    private val baseUrls = McCloudDomain.values().associateWith { domain ->
        McCloudEndpoint.normalize(
            endpoints.baseUrl(domain),
            "${domain.name.lowercase()}BaseUrl",
            allowInsecureLoopback,
        )
    }
    override suspend fun <T> request(
        domain: McCloudDomain,
        path: String,
        method: String,
        body: Any?,
        query: Map<String, Any?>,
        type: Type,
    ): T = withContext(ioDispatcher) {
        val url = McCloudEndpoint.resolve(baseUrls.getValue(domain), path).newBuilder().apply {
            query.forEach { (key, value) ->
                if (value != null && value.toString().isNotEmpty()) addQueryParameter(key, value.toString())
            }
        }.build()
        val requestBody = body?.let { gson.toJson(it).toRequestBody(JSON_MEDIA_TYPE) }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (requestBody != null) header("Content-Type", "application/json") }
            .method(method, if (method == "GET" || method == "HEAD") null else requestBody ?: EMPTY_BODY)
            .build()

        val response = try {
            callFactory.newCall(request).execute()
        } catch (error: IOException) {
            throw McCloudApiException(message = "网络错误", cause = error)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.code == 401) {
                onUnauthorized?.invoke()
                throw McCloudApiException(statusCode = 401, message = "登录已过期，请重新登录")
            }
            val json = parseJson(text, it.isSuccessful, it.code, path)
            val code = json?.get("code")?.takeIf { element -> element.isJsonPrimitive }
                ?.let { element -> runCatching { element.asInt }.getOrNull() }
            val message = json?.get("message")?.takeIf { element -> element.isJsonPrimitive }
                ?.let { element -> runCatching { element.asString }.getOrNull() }
            val success = json?.get("success")?.takeIf { element -> element.isJsonPrimitive }
                ?.let { element -> runCatching { element.asBoolean }.getOrNull() }
            if (code != null && code != 0) {
                throw McCloudApiException(it.code, code, cleanMcCloudMessage(message))
            }
            if (success == false) {
                throw McCloudApiException(it.code, code, cleanMcCloudMessage(message))
            }
            if (!it.isSuccessful) {
                throw McCloudApiException(it.code, code, cleanMcCloudMessage(message ?: "请求失败（${it.code}）"))
            }
            val payload = when {
                json == null -> null
                json.has("data") -> json.get("data")
                !json.has("code") -> json
                else -> null
            }
            try {
                if (type == Unit::class.java) {
                    @Suppress("UNCHECKED_CAST")
                    return@withContext Unit as T
                }
                if (payload == null || payload.isJsonNull) {
                    throw McCloudApiException(it.code, code, "云端响应缺少 data")
                }
                @Suppress("UNCHECKED_CAST")
                gson.fromJson<T>(payload, type)
            } catch (error: JsonParseException) {
                throw McCloudApiException(it.code, code, "云端响应格式异常（$path）", error)
            }
        }
    }

    override fun resetUnauthorizedState() {
        Unit
    }

    private fun parseJson(text: String, successful: Boolean, status: Int, path: String): JsonObject? {
        if (text.isBlank()) return null
        return try {
            gson.fromJson(text, JsonObject::class.java)
        } catch (error: JsonParseException) {
            if (successful) {
                throw McCloudApiException(status, message = "云端响应格式异常（$path：响应不是 JSON）", cause = error)
            }
            throw McCloudApiException(status, message = "请求失败（$status）", cause = error)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

internal inline suspend fun <reified T> McCloudRemote.call(
    domain: McCloudDomain,
    path: String,
    method: String = "GET",
    body: Any? = null,
    query: Map<String, Any?> = emptyMap(),
): T = request(domain, path, method, body, query, object : com.google.gson.reflect.TypeToken<T>() {}.type)
