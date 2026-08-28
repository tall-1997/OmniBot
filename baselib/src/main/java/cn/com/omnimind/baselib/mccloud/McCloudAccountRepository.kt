package cn.com.omnimind.baselib.mccloud

class McCloudSessionManager(
    private val cookieJar: McCloudCookieJar,
    private val remote: McCloudRemote,
) {
    @Volatile
    private var cachedUser: McCloudUser? = null
    private val unauthorizedHandled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isSignedIn(): Boolean = cookieJar.hasSession(McCloudDomain.MONKEY_CODE)
    fun currentUser(): McCloudUser? = cachedUser

    suspend fun refreshUser(): McCloudUser {
        val status = remote.call<McCloudUserStatus>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/status",
        )
        return (status.user ?: status.asUser()).also { user ->
            if (user.id.isBlank() && user.email.isBlank() && user.username.isBlank()) {
                throw McCloudApiException(message = "云端用户响应格式异常")
            }
            cachedUser = user
            unauthorizedHandled.set(false)
            remote.resetUnauthorizedState()
        }
    }

    fun clearSession(): Boolean {
        cachedUser = null
        return cookieJar.clear()
    }

    fun handleUnauthorized(): Boolean {
        if (!unauthorizedHandled.compareAndSet(false, true)) return false
        clearSession()
        McCloudEvents.emit(mapOf("type" to "sessionExpired"))
        return true
    }
}

data class McCloudUserStatus(
    val user: McCloudUser? = null,
    val id: String = "",
    val name: String = "",
    val username: String = "",
    val email: String = "",
    val avatar: String = "",
    @com.google.gson.annotations.SerializedName("avatar_url") val avatarUrl: String = "",
    val role: String = "",
    val team: McCloudTeam? = null,
) {
    fun asUser() = McCloudUser(id, name, username, email, avatarUrl.ifBlank { avatar }, role, team)
}

class McCloudAccountRepository(
    private val remote: McCloudRemote,
    private val captcha: McCloudCaptchaSolver,
    private val session: McCloudSessionManager,
) {
    suspend fun loginWithPassword(email: String, password: String): McCloudUser {
        require(email.trim().contains('@')) { "邮箱格式无效" }
        require(password.isNotEmpty()) { "密码不能为空" }
        val captchaToken = captcha.obtainCaptchaToken(McCloudDomain.MONKEY_CODE)
        remote.call<McCloudUserStatus>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/password-login",
            "POST",
            mapOf("email" to email.trim(), "password" to password, "captcha_token" to captchaToken),
        )
        return session.refreshUser()
    }

    suspend fun status(): McCloudUser = session.refreshUser()

    suspend fun logout() {
        try {
            remote.call<Unit>(McCloudDomain.MONKEY_CODE, "/api/v1/users/logout", "POST")
        } finally {
            session.clearSession()
        }
    }

    suspend fun deleteAccount() {
        remote.call<Unit>(McCloudDomain.MONKEY_CODE, "/api/v1/users/account", "DELETE")
        session.clearSession()
    }

    suspend fun bindEmail(email: String) {
        require(EMAIL_REGEX.matches(email.trim())) { "邮箱格式无效" }
        remote.call<Unit>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/email/bind-request",
            "PUT",
            mapOf("email" to email.trim()),
        )
    }

    companion object {
        private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
