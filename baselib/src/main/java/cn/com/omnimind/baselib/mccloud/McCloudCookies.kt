package cn.com.omnimind.baselib.mccloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

interface PersistentCookieStore {
    fun isAvailable(): Boolean = true
    fun read(domain: McCloudDomain): Set<String>
    fun write(domain: McCloudDomain, cookies: Set<String>): Boolean
    fun clear(domain: McCloudDomain? = null): Boolean
}

class EncryptedPersistentCookieStore(context: Context) : PersistentCookieStore {
    private var initializationError: Exception? = null
    private val preferences: SharedPreferences? = try {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { it.all }
    } catch (error: Exception) {
        initializationError = error
        null
    }

    override fun isAvailable(): Boolean = preferences != null

    private fun requirePreferences(): SharedPreferences = preferences
        ?: throw McCloudStorageUnavailableException(initializationError)

    @Synchronized
    override fun read(domain: McCloudDomain): Set<String> = try {
        requirePreferences().getStringSet(domain.storageKey, emptySet())?.toSet().orEmpty()
    } catch (error: McCloudStorageUnavailableException) {
        throw error
    } catch (error: Exception) {
        throw McCloudStorageUnavailableException(error)
    }

    @Synchronized
    override fun write(domain: McCloudDomain, cookies: Set<String>): Boolean = try {
        requirePreferences().edit().putStringSet(domain.storageKey, cookies).commit()
    } catch (error: McCloudStorageUnavailableException) {
        throw error
    } catch (error: Exception) {
        throw McCloudStorageUnavailableException(error)
    }

    @Synchronized
    override fun clear(domain: McCloudDomain?): Boolean = try {
        val editor = requirePreferences().edit()
        if (domain == null) editor.clear() else editor.remove(domain.storageKey)
        editor.commit()
    } catch (error: McCloudStorageUnavailableException) {
        throw error
    } catch (error: Exception) {
        throw McCloudStorageUnavailableException(error)
    }

    private val McCloudDomain.storageKey: String get() = name.lowercase()

    companion object {
        const val FILE_NAME = "mc_cloud_cookies"
    }
}

class McCloudCookieJar(
    endpoints: McCloudEndpoints,
    private val store: PersistentCookieStore,
    allowInsecureLoopback: Boolean = false,
) : CookieJar {
    private val baseUrls = McCloudDomain.values().associateWith { domain ->
        McCloudEndpoint.normalize(
                endpoints.baseUrl(domain),
                "${domain.name.lowercase()}BaseUrl",
                allowInsecureLoopback,
            ).toHttpUrl()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val domain = domainFor(url) ?: return
        val now = System.currentTimeMillis()
        val merged = load(domain).associateBy { it.storageIdentity }.toMutableMap()
        cookies.forEach { cookie ->
            if (cookie.expiresAt <= now) merged.remove(cookie.storageIdentity) else merged[cookie.storageIdentity] = cookie
        }
        if (!store.write(domain, merged.values.map(Cookie::toString).toSet())) {
            throw McCloudStorageUnavailableException()
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val domain = domainFor(url) ?: return emptyList()
        val now = System.currentTimeMillis()
        val all = load(domain)
        val active = all.filter { it.expiresAt > now }
        if (active.size != all.size && !store.write(domain, active.map(Cookie::toString).toSet())) {
            throw McCloudStorageUnavailableException()
        }
        return active.filter { it.matches(url) }
    }

    fun hasSession(domain: McCloudDomain): Boolean = load(domain).any {
        it.name == SESSION_COOKIE && it.expiresAt > System.currentTimeMillis()
    }
    fun cookieHeader(domain: McCloudDomain): String = loadForRequest(baseUrls.getValue(domain))
        .joinToString("; ") { "${it.name}=${it.value}" }

    fun importCookieHeader(domain: McCloudDomain, cookieHeader: String) {
        val url = baseUrls.getValue(domain)
        val cookies = cookieHeader.split(';').mapNotNull { part ->
            Cookie.parse(url, part.trim())
        }
        if (cookies.isNotEmpty()) saveFromResponse(url, cookies)
    }

    fun importCookies(domain: McCloudDomain, cookies: List<Cookie>, allowedNames: Set<String>) {
        val url = baseUrls.getValue(domain)
        val accepted = cookies.filter { cookie ->
            cookie.name in allowedNames && cookie.matches(url)
        }
        require(accepted.size == cookies.size) { "Cookie 域或名称不受信任" }
        if (accepted.isNotEmpty()) saveFromResponse(url, accepted)
    }

    fun domainForUrl(url: HttpUrl): McCloudDomain? = domainFor(url)

    fun clear(domain: McCloudDomain? = null): Boolean = store.clear(domain)

    private fun load(domain: McCloudDomain): List<Cookie> = store.read(domain).mapNotNull {
        Cookie.parse(baseUrls.getValue(domain), it)
    }

    private fun domainFor(url: HttpUrl): McCloudDomain? = baseUrls.entries
        .firstOrNull { (_, base) -> base.scheme == url.scheme && base.host == url.host && base.port == url.port }
        ?.key

    private val Cookie.storageIdentity: String get() = "$name|$domain|$path"

    companion object {
        private const val SESSION_COOKIE = "monkeycode_ai_session"
    }
}
