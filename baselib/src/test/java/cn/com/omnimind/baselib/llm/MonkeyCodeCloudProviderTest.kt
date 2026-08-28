package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MonkeyCodeCloudProviderTest {
    @Test
    fun extractsPromptUsingServerPriorityAcrossSupportedProtocols() {
        assertEquals(
            "anthropic-system",
            MonkeyCodeCloudProvider.extractSystemPrompt(
                """{"system":[{"type":"text","text":"anthropic-system"},{"type":"text","text":"dynamic"}],"instructions":"responses"}"""
            )
        )
        assertEquals(
            "responses\ndynamic",
            MonkeyCodeCloudProvider.extractSystemPrompt(
                """{"instructions":[{"text":"responses"},{"text":"dynamic"}],"messages":[{"role":"system","content":"chat"}]}"""
            )
        )
        assertEquals(
            "chat-system",
            MonkeyCodeCloudProvider.extractSystemPrompt(
                """{"messages":[{"role":"user","content":"hello"},{"role":"system","content":"chat-system"}]}"""
            )
        )
        assertEquals(
            "responses-input",
            MonkeyCodeCloudProvider.extractSystemPrompt(
                """{"input":[{"role":"developer","content":"responses-input"}]}"""
            )
        )
    }

    @Test
    fun anthropicSystemUsesOnlyFirstBlock() {
        assertThrows(IllegalArgumentException::class.java) {
            MonkeyCodeCloudProvider.extractSystemPrompt(
                """{"system":[{"text":""},{"text":"must-not-be-used"}]}"""
            )
        }
    }

    @Test
    fun signsPromptWithBackendCompatibleHmacSha256Vector() {
        assertEquals(
            "v1=040af9b9bf1319bcb756f04108bd7ffcb936a681a072cc381ad786d556595c64",
            MonkeyCodeCloudProvider.signSystemPrompt(
                signingSecret = "omas_test_secret",
                systemPrompt = "You are OhMyAgent, an AI coding agent for software engineering tasks.",
            )
        )
    }

    @Test
    fun mapsCloudInterfaceToReadOnlyProviderProfile() {
        val responses = MonkeyCodeCloudProvider.profile(
            id = "cloud-model",
            name = "Cloud model",
            proxyBaseUrl = "https://monkeycode-ai.com",
            interfaceType = "openai_responses",
        )
        val locked = MonkeyCodeCloudProvider.profile(
            id = "locked-model",
            name = "Locked model",
            proxyBaseUrl = "https://monkeycode-ai.com",
            interfaceType = "anthropic",
            locked = true,
        )

        assertEquals(MonkeyCodeCloudProvider.SOURCE_TYPE, responses.sourceType)
        assertEquals(OpenAiWireApi.RESPONSES, responses.wireApi)
        assertTrue(responses.readOnly)
        assertEquals("anthropic", locked.protocolType)
        assertFalse(locked.ready)
    }

    @Test
    fun projectsUsableModelsAndKeepsLockedEntriesVisible() {
        val projected = MonkeyCodeCloudProvider.projectModels(
            models = listOf(
                MonkeyCodeCloudModelDescriptor(
                    id = "public-1",
                    model = "monkeycode-pro-coder",
                    interfaceType = "openai_chat",
                    ownerType = "public",
                    locked = true,
                ),
                MonkeyCodeCloudModelDescriptor(
                    id = "hidden",
                    model = "hidden-model",
                    interfaceType = "openai_chat",
                    ownerType = "private",
                    hidden = true,
                ),
                MonkeyCodeCloudModelDescriptor(
                    id = "placeholder",
                    model = "monkeycode-basic",
                    interfaceType = "openai_chat",
                    ownerType = "public",
                ),
                MonkeyCodeCloudModelDescriptor(
                    id = "unknown-owner",
                    model = "unknown-model",
                    interfaceType = "openai_chat",
                    ownerType = "unknown",
                ),
            ),
            proxyBaseUrl = "https://monkeycode-ai.com",
        )

        assertEquals(1, projected.size)
        assertEquals("monkeycode-pro-coder", projected.single().modelId)
        assertTrue(projected.single().locked)
        assertFalse(projected.single().profile.ready)
    }

    @Test
    fun redactsOhMyAgentSignatureFromLogs() {
        val redacted = ProviderCustomHeaderUtils.redactHeadersForLog(
            mapOf(MonkeyCodeCloudProvider.SIGNATURE_HEADER to "v1=secret")
        )

        assertEquals("***", redacted[MonkeyCodeCloudProvider.SIGNATURE_HEADER])
    }

    @Test
    fun cloudCredentialBindingRequiresCloudProfileAndProxyOrigin() {
        val profile = MonkeyCodeCloudProvider.profile(
            id = "cloud-model",
            name = "Cloud model",
            proxyBaseUrl = "https://proxy.monkeycode-ai.com/v1",
            interfaceType = "openai_chat",
        )

        assertTrue(
            MonkeyCodeCloudProvider.shouldAttachCredential(
                profile,
                "https://proxy.monkeycode-ai.com/v1/chat/completions",
            ),
        )
        assertFalse(
            MonkeyCodeCloudProvider.shouldAttachCredential(
                profile,
                "https://proxy.monkeycode-ai.com.evil.example/v1/chat/completions",
            ),
        )
        assertFalse(
            MonkeyCodeCloudProvider.shouldAttachCredential(
                profile.copy(sourceType = "custom"),
                "https://proxy.monkeycode-ai.com/v1/chat/completions",
            ),
        )
    }
}
