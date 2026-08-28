package cn.com.omnimind.bot.ui.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McCloudPayloadSanitizerTest {
    @Test
    fun `removes sensitive fields recursively`() {
        val payload = mapOf(
            "id" to "model-1",
            "api_key" to "secret",
            "owner" to mapOf("accessToken" to "token", "name" to "user"),
            "items" to listOf(mapOf("signing_secret" to "sign", "model" to "safe")),
        )

        @Suppress("UNCHECKED_CAST")
        val sanitized = McCloudPayloadSanitizer.sanitize(payload) as Map<String, Any?>

        assertFalse(sanitized.containsKey("api_key"))
        assertEquals(mapOf("name" to "user"), sanitized["owner"])
        assertEquals(listOf(mapOf("model" to "safe")), sanitized["items"])
    }

    @Test
    fun `preserves ordinary cloud response fields`() {
        val payload = mapOf("uploadProgress" to 50, "locked" to true, "base_url" to "https://example.com")

        assertEquals(payload, McCloudPayloadSanitizer.sanitize(payload))
    }

    @Test
    fun `removes sensitive fields from websocket json strings`() {
        val payload = "{\"type\":\"chunk\",\"api_key\":\"secret\",\"data\":\"safe\"}"

        assertEquals("{\"type\":\"chunk\",\"data\":\"safe\"}", McCloudPayloadSanitizer.sanitize(payload))
    }

    @Test
    fun `redacts credentials from cloud error messages`() {
        assertEquals(
            "request failed api_key=[REDACTED]",
            McCloudPayloadSanitizer.sanitizeMessage("request failed api_key=omk-secret"),
        )
    }
}
