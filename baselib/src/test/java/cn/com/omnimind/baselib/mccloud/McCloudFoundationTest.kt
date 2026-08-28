package cn.com.omnimind.baselib.mccloud

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Type

class McCloudFoundationTest {
    @Test
    fun endpointNormalizationRemovesTrailingSlashAndRejectsUnsafeUrls() {
        assertEquals("https://example.com", McCloudEndpoint.normalize(" https://example.com/ ", "base"))
        assertEquals(
            "http://127.0.0.1:8080",
            McCloudEndpoint.normalize("http://127.0.0.1:8080", "base", allowInsecureLoopback = true),
        )
        assertFails<IllegalArgumentException> { McCloudEndpoint.normalize("http://example.com", "base", true) }
        assertFails<IllegalArgumentException> { McCloudEndpoint.normalize("https://example.com/api", "base") }
    }

    @Test
    fun cookieJarKeepsServiceDomainsIndependentAndRecognizesOnlySessionCookie() {
        val store = MemoryCookieStore()
        val jar = McCloudCookieJar(McCloudEndpoints(), store)
        val monkeyCode = "https://monkeycode-ai.com/api".toHttpUrl()
        val baizhi = "https://baizhi.cloud/api".toHttpUrl()
        jar.saveFromResponse(monkeyCode, listOf(Cookie.Builder().name("captcha").value("x").domain(monkeyCode.host).build()))
        jar.saveFromResponse(
            baizhi,
            listOf(Cookie.Builder().name("monkeycode_ai_session").value("baizhi").domain(baizhi.host).build()),
        )

        assertFalse(jar.hasSession(McCloudDomain.MONKEY_CODE))
        assertTrue(jar.hasSession(McCloudDomain.BAIZHI))
        assertEquals(listOf("captcha"), jar.loadForRequest(monkeyCode).map { it.name })
        assertEquals(listOf("monkeycode_ai_session"), jar.loadForRequest(baizhi).map { it.name })
    }

    @Test
    fun controlledCookieImportAcceptsOnlyNamedCookieOnTargetDomain() {
        val store = MemoryCookieStore()
        val jar = McCloudCookieJar(McCloudEndpoints(), store)
        val target = "https://monkeycode-ai.com/".toHttpUrl()
        val session = Cookie.Builder().name("monkeycode_ai_session").value("value").hostOnlyDomain(target.host).build()

        jar.importCookies(McCloudDomain.MONKEY_CODE, listOf(session), setOf("monkeycode_ai_session"))

        assertTrue(jar.hasSession(McCloudDomain.MONKEY_CODE))
        assertFails<IllegalArgumentException> {
            jar.importCookies(
                McCloudDomain.MONKEY_CODE,
                listOf(Cookie.Builder().name("other").value("value").hostOnlyDomain(target.host).build()),
                setOf("monkeycode_ai_session"),
            )
        }
    }

    @Test
    fun powUsesGoCapPrngVectorAndFindsValidNonce() = runBlocking {
        val solver = McCloudCaptchaSolver(NoopRemote(), maxNonce = 100_000)
        assertEquals("0bb9adb8", solver.prng("abc", 8))
        val challenge = McCloudCaptchaChallenge(McCloudCaptchaParameters(c = 2, s = 16, d = 2), "testtoken")
        val solutions = solver.solveChallenges(challenge)
        assertEquals(2, solutions.size)
        assertTrue(solutions.all { it in 0 until 100_000 })
    }

    @Test
    fun powRejectsDifficultyAboveConfiguredLimit() {
        val solver = McCloudCaptchaSolver(NoopRemote(), maxDifficulty = 3)
        assertFails<IllegalArgumentException> {
            runBlocking {
                solver.solveChallenges(McCloudCaptchaChallenge(McCloudCaptchaParameters(1, 16, 4), "token"))
            }
        }
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}

private class MemoryCookieStore : PersistentCookieStore {
    private val values = mutableMapOf<McCloudDomain, Set<String>>()
    override fun read(domain: McCloudDomain): Set<String> = values[domain].orEmpty()
    override fun write(domain: McCloudDomain, cookies: Set<String>): Boolean {
        values[domain] = cookies
        return true
    }
    override fun clear(domain: McCloudDomain?): Boolean {
        if (domain == null) values.clear() else values.remove(domain)
        return true
    }
}

private class NoopRemote : McCloudRemote {
    override suspend fun <T> request(
        domain: McCloudDomain,
        path: String,
        method: String,
        body: Any?,
        query: Map<String, Any?>,
        type: Type,
    ): T = throw UnsupportedOperationException()
}
