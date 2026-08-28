package cn.com.omnimind.baselib.mccloud

import android.content.Context
import cn.com.omnimind.baselib.account.EncryptedAccountTokenStore
import cn.com.omnimind.baselib.account.SharedPreferencesAiAccessModeStore
import okhttp3.OkHttpClient

/** Process-wide MonkeyCode cloud dependency container. */
object McCloud {
    @Volatile
    private var services: McCloudServices? = null

    fun initialize(
        context: Context,
        endpoints: McCloudEndpoints = McCloudEndpoints(),
        allowInsecureLoopback: Boolean = false,
    ) {
        if (services != null) return
        synchronized(this) {
            if (services != null) return
            migrateLegacyAccountSession(context.applicationContext)
            val cookieJar = McCloudCookieJar(
                endpoints,
                EncryptedPersistentCookieStore(context.applicationContext),
                allowInsecureLoopback,
            )
            val httpClient = OkHttpClient.Builder().cookieJar(cookieJar).build()
            lateinit var session: McCloudSessionManager
            val remote = McCloudApiClient(
                endpoints = endpoints,
                cookieJar = cookieJar,
                callFactory = httpClient,
                allowInsecureLoopback = allowInsecureLoopback,
                onUnauthorized = {
                    session.handleUnauthorized()
                },
            )
            val captcha = McCloudCaptchaSolver(remote)
            session = McCloudSessionManager(cookieJar, remote)
            services = McCloudServices(
                endpoints = endpoints,
                session = session,
                account = McCloudAccountRepository(remote, captcha, session),
                oauth = McCloudOAuthHandler(
                    endpoints,
                    remote,
                    captcha,
                    httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build(),
                    cookieJar,
                    session,
                    allowInsecureLoopback,
                ),
                cloud = McCloudCloudRepository(remote, captcha),
                git = McCloudGitRepository(remote),
                models = McCloudModelRepository(remote),
                projects = McCloudProjectRepository(remote),
                tasks = McCloudTaskStreamManager(
                    endpoints,
                    cookieJar,
                    httpClient,
                    session,
                    McCloudEvents::emit,
                    allowInsecureLoopback,
                ),
                files = McCloudFileManager(
                    endpoints,
                    cookieJar,
                    httpClient,
                    session,
                    McCloudEvents::emit,
                    allowInsecureLoopback = allowInsecureLoopback,
                ),
            )
        }
    }

    fun get(): McCloudServices = services ?: error("McCloud has not been initialized")

    private fun migrateLegacyAccountSession(context: Context) {
        val migration = context.getSharedPreferences(MIGRATION_STORE, Context.MODE_PRIVATE)
        if (migration.getBoolean(MIGRATION_KEY, false)) return
        val tokensCleared = EncryptedAccountTokenStore(context).clear()
        SharedPreferencesAiAccessModeStore(context).clear()
        if (!tokensCleared) return
        migration.edit().putBoolean(MIGRATION_KEY, true).apply()
    }

    private const val MIGRATION_STORE = "mc_cloud_migrations"
    private const val MIGRATION_KEY = "legacy_omni_account_session_cleared_v1"
}

data class McCloudServices(
    val endpoints: McCloudEndpoints,
    val session: McCloudSessionManager,
    val account: McCloudAccountRepository,
    val oauth: McCloudOAuthHandler,
    val cloud: McCloudCloudRepository,
    val git: McCloudGitRepository,
    val models: McCloudModelRepository,
    val projects: McCloudProjectRepository,
    val tasks: McCloudTaskStreamManager,
    val files: McCloudFileManager,
)

object McCloudEvents {
    private val listeners = linkedMapOf<Any, (Map<String, Any?>) -> Unit>()

    @Synchronized
    fun subscribe(listener: (Map<String, Any?>) -> Unit): () -> Unit {
        val subscriptionId = Any()
        listeners[subscriptionId] = listener
        return { synchronized(McCloudEvents) { listeners.remove(subscriptionId) } }
    }

    fun emit(event: Map<String, Any?>) {
        val snapshot = synchronized(this) { listeners.values.toList() }
        snapshot.forEach { it(event) }
    }
}
