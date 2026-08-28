package cn.com.omnimind.baselib.mccloud

import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class McCloudAlipayAuthorization(
    @com.google.gson.annotations.SerializedName("auth_info") val authInfo: String = "",
    @com.google.gson.annotations.SerializedName("request_id") val requestId: String = "",
    @com.google.gson.annotations.SerializedName("expires_at") val expiresAt: String? = null,
)

data class McCloudOAuthLoginResult(
    @com.google.gson.annotations.SerializedName("pending_phone_token") val pendingPhoneToken: String? = null,
)

data class McCloudOAuthUrl(val url: String = "")

enum class McCloudWechatState { WAITING, SCANNED, CANCELED, EXPIRED, COMPLETED }

data class McCloudWechatSession(
    val uuid: String,
    val state: String,
    val callbackUrl: String,
    val lpBase: String,
    val qrDataUrl: String,
)

class McCloudOAuthHandler(
    private val endpoints: McCloudEndpoints,
    private val remote: McCloudRemote,
    private val captcha: McCloudCaptchaSolver,
    private val callFactory: Call.Factory,
    private val cookieJar: McCloudCookieJar,
    private val session: McCloudSessionManager,
    allowInsecureLoopback: Boolean = false,
) {
    private val wechatMutex = Mutex()
    @Volatile
    private var wechatSession: McCloudWechatSession? = null
    private val wechatGeneration = AtomicLong(0)
    private val activeWechatCall = AtomicReference<Call?>(null)
    private val serviceUrls = McCloudDomain.values().associateWith { domain ->
        McCloudEndpoint.normalize(
            endpoints.baseUrl(domain),
            "${domain.name.lowercase()}BaseUrl",
            allowInsecureLoopback,
        ).toHttpUrlOrNull()!!
    }

    suspend fun sendPhoneCode(phone: String, pendingPhoneToken: String? = null) {
        require(PHONE_REGEX.matches(phone)) { "手机号格式无效" }
        val captchaToken = captcha.obtainCaptchaToken(McCloudDomain.BAIZHI)
        remote.call<Unit>(
            McCloudDomain.BAIZHI,
            "/api/v1/user/phone_code",
            "POST",
            mapOf("phone" to phone, "kind" to "login", "token" to pendingPhoneToken, "captcha_token" to captchaToken),
        )
    }

    suspend fun loginPhone(phone: String, code: String): McCloudUser {
        require(PHONE_REGEX.matches(phone)) { "手机号格式无效" }
        require(code.isNotBlank()) { "验证码不能为空" }
        val captchaToken = captcha.obtainCaptchaToken(McCloudDomain.BAIZHI)
        remote.call<Unit>(
            McCloudDomain.BAIZHI,
            "/api/v1/user/login/phone",
            "POST",
            mapOf("phone" to phone, "code" to code.trim(), "captcha_token" to captchaToken),
        )
        return bridgeBaizhiSession()
    }

    suspend fun prepareAlipayAppLogin(): McCloudAlipayAuthorization {
        val result = remote.call<McCloudAlipayAuthorization>(
            McCloudDomain.BAIZHI,
            "/api/v1/user/oauth/app-login/prepare",
            "POST",
            mapOf("platform" to "alipay_app"),
        )
        require(result.authInfo.isNotBlank() && result.requestId.isNotBlank()) { "支付宝授权参数响应格式异常" }
        return result
    }

    suspend fun loginAlipayApp(code: String, requestId: String): McCloudOAuthLoginResult =
        appLogin("alipay_app", code, requestId)

    suspend fun loginDouyinApp(code: String): McCloudOAuthLoginResult = appLogin("douyin_app", code, null)

    suspend fun completePhoneBind(token: String, phone: String, code: String) {
        val captchaToken = captcha.obtainCaptchaToken(McCloudDomain.BAIZHI)
        remote.call<Unit>(
            McCloudDomain.BAIZHI,
            "/api/v1/user/oauth/complete-phone",
            "POST",
            mapOf("token" to token, "phone" to phone, "code" to code, "captcha_token" to captchaToken),
        )
    }

    suspend fun completePhoneBindAndBridge(token: String, phone: String, code: String): McCloudUser {
        completePhoneBind(token, phone, code)
        return bridgeBaizhiSession()
    }

    fun getGitHubLoginUrl(inviterId: String? = null): String {
        val base = McCloudEndpoint.normalize(endpoints.monkeyCode, "monkeyCodeBaseUrl")
        return McCloudEndpoint.resolve(base, "/api/v1/users/login").newBuilder()
            .addQueryParameter("redirect", "")
            .addQueryParameter("inviter_id", inviterId.orEmpty())
            .build().toString()
    }

    suspend fun getBaizhiOAuthLoginUrl(platform: String, redirectUrl: String): String {
        require(platform == "github") { "百智云 OAuth 平台无效" }
        require(isServiceUrl(redirectUrl.toHttpUrlOrNull(), McCloudDomain.MONKEY_CODE)) { "OAuth 回调地址无效" }
        val url = remote.call<McCloudOAuthUrl>(
            McCloudDomain.BAIZHI,
            "/api/v1/user/oauth/login",
            query = mapOf("platform" to platform, "redirect_url" to redirectUrl),
        ).url.takeIf(String::isNotBlank)
            ?: throw McCloudApiException(message = "未获取到授权地址，请重试")
        require(isGithubAuthorizeUrl(url.toHttpUrlOrNull())) { "GitHub 授权地址域名无效" }
        return url
    }

    suspend fun startWechatLogin(): McCloudWechatSession = wechatMutex.withLock {
        val generation = wechatGeneration.incrementAndGet()
        val redirect = McCloudEndpoint.normalize(endpoints.monkeyCode, "monkeyCodeBaseUrl") + "/"
        val oauth = remote.call<McCloudOAuthUrl>(
            McCloudDomain.BAIZHI,
            "/api/v1/user/oauth/login",
            query = mapOf("platform" to "wechat", "redirect_url" to redirect),
        )
        val authUrl = oauth.url.toHttpUrlOrNull() ?: throw McCloudApiException(message = "微信授权地址异常")
        require(isWechatAuthorizeUrl(authUrl)) { "微信授权地址域名无效" }
        val state = authUrl.queryParameter("state").orEmpty()
        val callback = authUrl.queryParameter("redirect_uri").orEmpty()
        val callbackUrl = callback.toHttpUrlOrNull()
        require(state.isNotEmpty() && isServiceUrl(callbackUrl, McCloudDomain.BAIZHI)) { "微信授权地址缺少必要参数" }
        val page = executeWechat(authUrl, generation)
        val uuid = QRCODE_REGEX.find(page.toString(Charsets.UTF_8))?.groupValues?.get(1)
            ?: throw McCloudApiException(message = "微信授权页里未找到二维码")
        val imageUrl = authUrl.resolve("/connect/qrcode/$uuid")
            ?: throw McCloudApiException(message = "微信二维码地址异常")
        val image = executeWechat(imageUrl, generation)
        val lpBase = authUrl.newBuilder().host("lp.${authUrl.host}").encodedPath("/").query(null).build()
            .toString().trimEnd('/')
        McCloudWechatSession(
            uuid,
            state,
            callback,
            lpBase,
            "data:image/jpeg;base64,${Base64.encodeToString(image, Base64.NO_WRAP)}",
        ).also { wechatSession = it }
    }

    suspend fun pollWechatLogin(): McCloudWechatState = wechatMutex.withLock {
        val session = wechatSession ?: throw IllegalStateException("没有进行中的微信扫码会话")
        val generation = wechatGeneration.get()
        val pollUrl = session.lpBase.toHttpUrlOrNull()!!.newBuilder()
            .addEncodedPathSegments("connect/l/qrconnect")
            .addQueryParameter("uuid", session.uuid)
            .addQueryParameter("_", System.currentTimeMillis().toString())
            .build()
        require(isWechatLongPollUrl(pollUrl)) { "微信轮询地址域名无效" }
        val text = executeWechat(pollUrl, generation).toString(Charsets.UTF_8)
        val code = WX_ERROR_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw McCloudApiException(message = "微信扫码状态响应异常")
        when (code) {
            408 -> McCloudWechatState.WAITING
            404 -> McCloudWechatState.SCANNED
            403 -> finishWechat(McCloudWechatState.CANCELED)
            402, 500 -> finishWechat(McCloudWechatState.EXPIRED)
            405 -> {
                val wxCode = WX_CODE_REGEX.find(text)?.groupValues?.get(1).orEmpty()
                require(wxCode.isNotEmpty()) { "微信扫码成功响应缺少授权码" }
                val callback = session.callbackUrl.toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("code", wxCode)
                    .addQueryParameter("state", session.state)
                    .build()
                executeWechat(callback, generation)
                bridgeBaizhiSession()
                finishWechat(McCloudWechatState.COMPLETED)
            }
            else -> throw McCloudApiException(message = "未知微信扫码状态：$code")
        }
    }

    fun cancelWechatLogin() {
        wechatGeneration.incrementAndGet()
        wechatSession = null
        activeWechatCall.getAndSet(null)?.cancel()
    }

    suspend fun bridgeBaizhiSession(): McCloudUser {
        require(cookieJar.cookieHeader(McCloudDomain.BAIZHI).isNotBlank()) { "请先登录百智云账号" }
        var current = McCloudEndpoint.resolve(
            serviceUrls.getValue(McCloudDomain.MONKEY_CODE).toString(),
            "/api/v1/users/login",
        ).newBuilder().addQueryParameter("redirect", "").addQueryParameter("inviter_id", "").build()
        repeat(MAX_BRIDGE_HOPS) {
            if (isAuthorizePage(current)) current = authorizePageToApi(current)
            val response = executeBridgeHop(current)
            response.use {
                if (it.code in 300..399) {
                    val location = it.header("Location") ?: throw McCloudApiException(message = "登录桥接重定向缺少地址")
                    current = current.resolve(location) ?: throw McCloudApiException(message = "登录桥接重定向地址无效")
                    require(serviceDomain(current) != null) { "登录桥接跳转域名无效" }
                } else {
                    if (it.code == 401) session.handleUnauthorized()
                    if (!it.isSuccessful) throw McCloudApiException(it.code, message = "登录桥接失败（${it.code}）")
                    return session.refreshUser()
                }
            }
        }
        throw McCloudApiException(message = "登录桥接重定向次数过多")
    }

    suspend fun completeControlledCallback(url: String): McCloudUser {
        var current = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("OAuth 回调地址无效")
        require(isControlledCallback(current)) { "OAuth 回调地址不受信任" }
        repeat(MAX_BRIDGE_HOPS) {
            val response = executeBridgeHop(current)
            response.use {
                if (it.code in 300..399) {
                    current = current.resolve(it.header("Location").orEmpty())
                        ?: throw McCloudApiException(message = "OAuth 回调重定向地址无效")
                    require(serviceDomain(current) != null) { "OAuth 回调跳转域名无效" }
                } else {
                    if (!it.isSuccessful) throw McCloudApiException(it.code, message = "OAuth 回调失败（${it.code}）")
                    return if (serviceDomain(current) == McCloudDomain.BAIZHI) {
                        bridgeBaizhiSession()
                    } else {
                        session.refreshUser()
                    }
                }
            }
        }
        throw McCloudApiException(message = "OAuth 回调重定向次数过多")
    }

    fun importControlledSessionCookie(domain: McCloudDomain, cookieHeader: String) {
        require(domain == McCloudDomain.MONKEY_CODE) { "仅允许导入 MonkeyCode 会话" }
        val base = serviceUrls.getValue(domain)
        val cookies = cookieHeader.split(';').mapNotNull { Cookie.parse(base, it.trim()) }
        require(cookies.isNotEmpty()) { "Cookie 内容无效" }
        cookieJar.importCookies(domain, cookies, setOf(SESSION_COOKIE))
    }

    private suspend fun appLogin(platform: String, code: String, requestId: String?): McCloudOAuthLoginResult {
        require(code.isNotBlank()) { "授权码不能为空" }
        return remote.call(
            McCloudDomain.BAIZHI,
            "/api/v1/user/oauth/app-login",
            "POST",
            mapOf("platform" to platform, "code" to code, "request_id" to requestId),
        )
    }

    private fun executeWechat(url: HttpUrl, generation: Long): ByteArray {
        check(wechatGeneration.get() == generation) { "微信登录已取消" }
        val call = callFactory.newCall(Request.Builder().url(url).build())
        activeWechatCall.set(call)
        val response = try {
            call.execute()
        } catch (error: IOException) {
            if (call.isCanceled()) throw kotlinx.coroutines.CancellationException("微信登录已取消")
            throw McCloudApiException(message = "微信登录网络请求失败", cause = error)
        } finally {
            activeWechatCall.compareAndSet(call, null)
        }
        response.use {
            if (wechatGeneration.get() != generation) {
                throw kotlinx.coroutines.CancellationException("微信登录已取消")
            }
            if (!it.isSuccessful && it.code !in 300..399) {
                throw McCloudApiException(statusCode = it.code, message = "微信登录请求失败（${it.code}）")
            }
            return it.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun executeBridgeHop(url: HttpUrl) = callFactory.newCall(Request.Builder().url(url).get().build()).execute()

    private fun isAuthorizePage(url: HttpUrl): Boolean =
        isServiceUrl(url, McCloudDomain.BAIZHI) && url.encodedPath == "/oauth/authorize"

    private fun authorizePageToApi(page: HttpUrl): HttpUrl {
        val redirect = page.queryParameter("redirect_uri") ?: page.queryParameter("redirect_url")
        val required = mapOf(
            "client_id" to page.queryParameter("client_id"),
            "redirect_uri" to redirect,
            "scope" to page.queryParameter("scope"),
            "state" to page.queryParameter("state"),
            "response_type" to (page.queryParameter("response_type") ?: "code"),
        )
        require(required.values.all { !it.isNullOrBlank() }) { "登录桥接授权参数不完整" }
        return McCloudEndpoint.resolve(
            serviceUrls.getValue(McCloudDomain.BAIZHI).toString(),
            "/api/v1/oauth/authorize",
        ).newBuilder().apply { required.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
    }

    private fun serviceDomain(url: HttpUrl): McCloudDomain? =
        McCloudDomain.values().firstOrNull { isServiceUrl(url, it) }

    private fun isControlledCallback(url: HttpUrl): Boolean =
        serviceDomain(url) != null && CALLBACK_PATH_REGEX.matches(url.encodedPath)

    private fun isServiceUrl(url: HttpUrl?, domain: McCloudDomain): Boolean {
        val base = serviceUrls.getValue(domain)
        return url != null &&
            (url.isHttps || base.scheme == "http" && url.scheme == "http") &&
            url.host == base.host && url.port == base.port
    }

    private fun isWechatAuthorizeUrl(url: HttpUrl): Boolean =
        url.isHttps && url.port == 443 && url.host in WECHAT_AUTHORIZE_HOSTS

    private fun isWechatLongPollUrl(url: HttpUrl): Boolean =
        url.isHttps && url.port == 443 && url.host.startsWith("lp.") && url.host.removePrefix("lp.") in WECHAT_AUTHORIZE_HOSTS

    private fun isGithubAuthorizeUrl(url: HttpUrl?): Boolean =
        url != null && url.isHttps && url.port == 443 && url.host in GITHUB_AUTHORIZE_HOSTS

    private fun finishWechat(state: McCloudWechatState): McCloudWechatState {
        wechatSession = null
        return state
    }

    companion object {
        private val PHONE_REGEX = Regex("^1[3-9]\\d{9}$")
        private val QRCODE_REGEX = Regex("/connect/qrcode/([A-Za-z0-9_-]+)")
        private val WX_ERROR_REGEX = Regex("wx_errcode=(\\d+)")
        private val WX_CODE_REGEX = Regex("wx_code=['\"]([^'\"]*)['\"]")
        private const val MAX_BRIDGE_HOPS = 12
        private const val SESSION_COOKIE = "monkeycode_ai_session"
        private val WECHAT_AUTHORIZE_HOSTS = setOf("open.weixin.qq.com", "open.wechat.com")
        private val GITHUB_AUTHORIZE_HOSTS = setOf("github.com", "www.github.com")
        private val CALLBACK_PATH_REGEX = Regex("^/api/v1/(users/)?oauth/[a-z0-9_-]+/callback$")
    }
}
