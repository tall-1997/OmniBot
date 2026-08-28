package cn.com.omnimind.baselib.mccloud

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class McCloudCaptchaParameters(val c: Int, val s: Int, val d: Int)
data class McCloudCaptchaChallenge(
    val challenge: McCloudCaptchaParameters,
    val token: String,
    val expires: Long? = null,
)
data class McCloudCaptchaRedeem(
    val success: Boolean = false,
    val token: String? = null,
    val message: String? = null,
)

class McCloudCaptchaSolver(
    private val remote: McCloudRemote,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxNonce: Int = 5_000_000,
    private val maxChallenges: Int = 100,
    private val maxDifficulty: Int = 6,
) {
    suspend fun obtainCaptchaToken(domain: McCloudDomain): String = SOLVE_MUTEX.withLock {
        val challenge = remote.call<McCloudCaptchaChallenge>(
            domain,
            "/api/v1/public/captcha/challenge",
            "POST",
        )
        val solutions = solveChallenges(challenge)
        val redeem = remote.call<McCloudCaptchaRedeem>(
            domain,
            "/api/v1/public/captcha/redeem",
            "POST",
            mapOf("token" to challenge.token, "solutions" to solutions),
        )
        redeem.token?.takeIf { redeem.success && it.isNotBlank() }
            ?: throw McCloudApiException(message = cleanMcCloudMessage(redeem.message ?: "验证码校验失败"))
    }

    suspend fun solveChallenges(input: McCloudCaptchaChallenge): IntArray = withContext(dispatcher) {
        val challenge = input.challenge
        require(challenge.c in 1..maxChallenges) { "验证码质询数量超出上限" }
        require(challenge.s in 1..256) { "验证码盐长度无效" }
        require(challenge.d in 1..maxDifficulty) { "验证码难度超出上限" }
        require(input.token.isNotBlank()) { "验证码 token 为空" }
        IntArray(challenge.c) { index ->
            val sequence = index + 1
            solveOne(
                salt = prng(input.token + sequence, challenge.s),
                target = prng(input.token + sequence + "d", challenge.d),
            )
        }
    }

    internal fun prng(seed: String, length: Int): String {
        var state = fnv1a32(seed)
        val result = StringBuilder(length + 8)
        while (result.length < length) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            result.append(state.toUInt().toString(16).padStart(8, '0'))
        }
        return result.substring(0, length)
    }

    private fun solveOne(salt: String, target: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        val targetNibbles = target.map { it.digitToInt(16) }
        for (nonce in 0 until maxNonce) {
            val hash = digest.digest((salt + nonce).toByteArray(StandardCharsets.UTF_8))
            if (targetNibbles.indices.all { index ->
                    val byte = hash[index / 2].toInt() and 0xff
                    val nibble = if (index % 2 == 0) byte ushr 4 else byte and 0x0f
                    nibble == targetNibbles[index]
                }
            ) return nonce
        }
        throw McCloudApiException(message = "验证码计算超时，请重试")
    }

    private fun fnv1a32(seed: String): Int {
        var hash = 0x811c9dc5u
        seed.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toInt() and 0xff).toUInt()
            hash *= 0x01000193u
        }
        return hash.toInt()
    }

    companion object {
        private val SOLVE_MUTEX = Mutex()
    }
}
