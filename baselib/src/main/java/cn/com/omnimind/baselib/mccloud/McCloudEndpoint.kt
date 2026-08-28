package cn.com.omnimind.baselib.mccloud

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object McCloudEndpoint {
    fun normalize(raw: String, label: String, allowInsecureLoopback: Boolean = false): String {
        val url = raw.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("$label is invalid")
        require(url.query == null && url.fragment == null) { "$label must not contain query or fragment" }
        require(url.encodedPath == "/") { "$label must not contain a path" }
        require(url.scheme == "https" || allowInsecureLoopback && url.scheme == "http" && url.isLoopback()) {
            "$label must use HTTPS"
        }
        return url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
    }

    fun resolve(baseUrl: String, path: String): HttpUrl {
        require(path.startsWith('/')) { "path must start with /" }
        require(!path.startsWith("//")) { "path must be relative to the configured service" }
        return baseUrl.toHttpUrlOrNull()!!.newBuilder()
            .encodedPath(path.substringBefore('?'))
            .apply {
                path.substringAfter('?', "").takeIf(String::isNotEmpty)?.let(::encodedQuery)
            }
            .build()
    }

    private fun HttpUrl.isLoopback(): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"
}
