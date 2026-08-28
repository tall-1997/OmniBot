package cn.com.omnimind.assists.controller.http

import cn.com.omnimind.baselib.llm.MonkeyCodeCloudCredential
import cn.com.omnimind.baselib.llm.MonkeyCodeCloudProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpControllerMonkeyCodeCloudTest {
    private val credential = MonkeyCodeCloudCredential(
        keyId = "key-id",
        apiKey = "omk-test-key",
        signingSecret = "omas_test_secret",
    )

    @Test
    fun cloudHeadersOverrideStaticAuthenticationOnFinalRequest() {
        val headers = HttpController.applyMonkeyCodeCloudHeaders(
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer stale",
                "X-Api-Key" to "stale",
            ),
            credential = credential,
            requestJson = """{"messages":[{"role":"system","content":"system prompt"}]}""",
        )

        assertEquals("Bearer omk-test-key", headers["Authorization"])
        assertEquals("omk-test-key", headers["X-Api-Key"])
        assertTrue(headers.getValue(MonkeyCodeCloudProvider.SIGNATURE_HEADER).startsWith("v1="))
    }

    @Test
    fun byokHeadersRemainUnchanged() {
        val source = mapOf("Authorization" to "Bearer byok")

        val headers = HttpController.applyMonkeyCodeCloudHeaders(source, null, null)

        assertEquals(source, headers)
        assertFalse(headers.containsKey(MonkeyCodeCloudProvider.SIGNATURE_HEADER))
    }

    @Test
    fun cloudAuthenticationFailuresTriggerOneCredentialRenewal() {
        val headers = mapOf(MonkeyCodeCloudProvider.SIGNATURE_HEADER to "v1=signature")

        assertTrue(HttpController.shouldRenewMonkeyCodeCloudCredential(401, headers))
        assertTrue(HttpController.shouldRenewMonkeyCodeCloudCredential(403, headers))
        assertFalse(HttpController.shouldRenewMonkeyCodeCloudCredential(400, headers))
    }

    @Test
    fun byokAuthenticationFailuresDoNotTriggerCloudCredentialRenewal() {
        val headers = mapOf("Authorization" to "Bearer byok")

        assertFalse(HttpController.shouldRenewMonkeyCodeCloudCredential(401, headers))
        assertFalse(HttpController.shouldRenewMonkeyCodeCloudCredential(403, headers))
    }

    @Test
    fun sensitiveQueryValuesAreRedactedFromLoggedUrl() {
        val redacted = HttpController.redactUrlForLog(
            "https://cloud.example/models?api_key=secret&provider=openai",
        )

        assertTrue(redacted.contains("api_key=%5BREDACTED%5D"))
        assertTrue(redacted.contains("provider=openai"))
        assertFalse(redacted.contains("secret"))
    }
}
