package cn.com.omnimind.baselib.llm

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class MonkeyCodeCloudCredential(
    val keyId: String,
    val apiKey: String,
    val signingSecret: String,
) {
    init {
        require(keyId.isNotBlank()) { "MonkeyCode cloud key id is empty" }
        require(apiKey.isNotBlank()) { "MonkeyCode cloud API key is empty" }
        require(signingSecret.isNotBlank()) { "MonkeyCode cloud signing secret is empty" }
    }
}

data class MonkeyCodeCloudModelDescriptor(
    val id: String,
    val model: String,
    val displayName: String = model,
    val interfaceType: String,
    val ownerType: String,
    val hidden: Boolean = false,
    val locked: Boolean = false,
)

data class MonkeyCodeCloudModelProjection(
    val profile: ModelProviderProfile,
    val modelId: String,
    val ownerType: String,
    val locked: Boolean,
)

/** Boundary implemented by the account repository once cloud provisioning is available. */
interface MonkeyCodeCloudCredentialProvisioner {
    suspend fun provision(): MonkeyCodeCloudCredential

    suspend fun revoke(keyId: String)
}

object MonkeyCodeCloudProvider {
    const val SOURCE_TYPE = "monkeycode"
    const val ROUTE_TAG = "monkeycode_cloud"
    const val SIGNATURE_HEADER = "X-OhMyAgent-Signature"
    const val DEFAULT_PROXY_BASE_URL = "https://proxy.monkeycode-ai.com/v1"
    private val ownerTypes = setOf("public", "private", "team")
    private val placeholders = setOf("monkeycode-basic", "monkeycode-pro", "monkeycode-ultra")

    fun isCloudSource(sourceType: String?): Boolean =
        sourceType?.trim()?.equals(SOURCE_TYPE, ignoreCase = true) == true

    fun profile(
        id: String,
        name: String,
        proxyBaseUrl: String,
        interfaceType: String,
        locked: Boolean = false,
    ): ModelProviderProfile {
        val (protocolType, wireApi) = when (interfaceType.trim().lowercase()) {
            "openai_chat" -> "openai_compatible" to OpenAiWireApi.CHAT_COMPLETIONS
            "openai_responses" -> "openai_compatible" to OpenAiWireApi.RESPONSES
            "anthropic" -> "anthropic" to OpenAiWireApi.CHAT_COMPLETIONS
            else -> throw IllegalArgumentException("Unsupported MonkeyCode model interface")
        }
        val normalizedProxyBaseUrl = ModelProviderConfigStore.normalizeBaseUrl(proxyBaseUrl)
            ?: throw IllegalArgumentException("MonkeyCode proxy base URL is invalid")
        return ModelProviderProfile(
            id = id.trim().also { require(it.isNotEmpty()) { "MonkeyCode model id is empty" } },
            name = name.trim().also { require(it.isNotEmpty()) { "MonkeyCode model name is empty" } },
            baseUrl = normalizedProxyBaseUrl,
            sourceType = SOURCE_TYPE,
            readOnly = true,
            ready = !locked,
            statusText = if (locked) "当前会员档位不可用" else null,
            protocolType = protocolType,
            wireApi = wireApi,
        )
    }

    fun projectModels(
        models: List<MonkeyCodeCloudModelDescriptor>,
        proxyBaseUrl: String,
    ): List<MonkeyCodeCloudModelProjection> {
        return models.mapNotNull { descriptor ->
            val id = descriptor.id.trim()
            val model = descriptor.model.trim()
            val ownerType = descriptor.ownerType.trim().lowercase()
            if (descriptor.hidden || id.isEmpty() || model.isEmpty() ||
                ownerType !in ownerTypes || model.lowercase() in placeholders
            ) {
                return@mapNotNull null
            }
            runCatching {
                MonkeyCodeCloudModelProjection(
                    profile = profile(
                        id = "$SOURCE_TYPE-$id",
                        name = descriptor.displayName.trim().ifEmpty { model },
                        proxyBaseUrl = proxyBaseUrl,
                        interfaceType = descriptor.interfaceType,
                        locked = descriptor.locked,
                    ).copy(modelIds = listOf(model)),
                    modelId = model,
                    ownerType = ownerType,
                    locked = descriptor.locked,
                )
            }.getOrNull()
        }
    }

    fun inventoryModels(profile: ModelProviderProfile): List<ProviderModelOption> {
        if (!isCloudSource(profile.sourceType) || !profile.ready) return emptyList()
        return profile.modelIds.map { modelId ->
            ProviderModelOption(
                id = modelId,
                displayName = profile.name,
                ownedBy = SOURCE_TYPE,
            )
        }
    }

    fun synchronizeProfile(
        profile: ModelProviderProfile,
        previous: ModelProviderProfile?,
    ): ModelProviderProfile {
        val unchanged = previous != null &&
            profile.name == previous.name &&
            profile.baseUrl == previous.baseUrl &&
            profile.sourceType == previous.sourceType &&
            profile.readOnly == previous.readOnly &&
            profile.ready == previous.ready &&
            profile.statusText == previous.statusText &&
            profile.protocolType == previous.protocolType &&
            profile.wireApi == previous.wireApi &&
            profile.modelIds == previous.modelIds
        return profile.copy(
            revision = if (unchanged) {
                previous.revision
            } else {
                (previous?.revision ?: 0L) + 1L
            },
        )
    }

    fun findOverrideProfile(
        profiles: List<ModelProviderProfile>,
        apiBase: String?,
        modelId: String?,
    ): ModelProviderProfile? {
        val normalizedModel = modelId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return profiles.firstOrNull { profile ->
            isCloudSource(profile.sourceType) &&
                ModelProviderConfigStore.sameCanonicalEndpoint(profile.baseUrl, apiBase.orEmpty()) &&
                normalizedModel in profile.modelIds
        }
    }

    fun extractSystemPrompt(requestBodyJson: String): String {
        val payload = runCatching { JsonParser.parseString(requestBodyJson).asJsonObject }
            .getOrElse { throw IllegalArgumentException("Invalid OhMyAgent request body", it) }

        decodeFirstPromptBlock(payload.get("system"))?.let { return it }
        decodePrompt(payload.get("instructions"))?.let { return it }
        findMessagePrompt(payload.getAsJsonArray("messages"), setOf("system"))?.let { return it }
        findMessagePrompt(payload.getAsJsonArray("input"), setOf("developer", "system"))?.let {
            return it
        }
        throw IllegalArgumentException("OhMyAgent system prompt not found")
    }

    fun signSystemPrompt(signingSecret: String, systemPrompt: String): String {
        require(signingSecret.isNotEmpty()) { "MonkeyCode cloud signing secret is empty" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(systemPrompt.toByteArray(StandardCharsets.UTF_8))
        return "v1=" + digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun requestHeaders(credential: MonkeyCodeCloudCredential, requestBodyJson: String): Map<String, String> {
        return linkedMapOf(
            "Authorization" to "Bearer ${credential.apiKey}",
            "X-Api-Key" to credential.apiKey,
            SIGNATURE_HEADER to signSystemPrompt(
                signingSecret = credential.signingSecret,
                systemPrompt = extractSystemPrompt(requestBodyJson),
            ),
        )
    }

    fun shouldAttachCredential(profile: ModelProviderProfile?, requestUrl: String): Boolean {
        if (profile == null || !isCloudSource(profile.sourceType)) return false
        val profileBase = profile.baseUrl.trim()
        val target = requestUrl.trim()
        val profileUrl = runCatching { java.net.URI(profileBase) }.getOrNull() ?: return false
        val targetUrl = runCatching { java.net.URI(target) }.getOrNull() ?: return false
        return profileUrl.scheme == targetUrl.scheme &&
            profileUrl.host == targetUrl.host &&
            effectivePort(profileUrl) == effectivePort(targetUrl) &&
            (targetUrl.path == profileUrl.path || targetUrl.path.startsWith(profileUrl.path.trimEnd('/') + "/"))
    }

    private fun effectivePort(uri: java.net.URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun findMessagePrompt(
        messages: com.google.gson.JsonArray?,
        acceptedRoles: Set<String>,
    ): String? {
        if (messages == null) return null
        for (item in messages) {
            val message = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (message.get("role")?.asString in acceptedRoles) {
                return decodePrompt(message.get("content"))
            }
        }
        return null
    }

    private fun decodeFirstPromptBlock(raw: JsonElement?): String? {
        if (raw == null || raw.isJsonNull) return null
        if (raw.isJsonPrimitive) return raw.asString.takeIf(String::isNotEmpty)
        val parts = raw.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return parts.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("text")?.asString?.takeIf(String::isNotEmpty)
    }

    private fun decodePrompt(raw: JsonElement?): String? {
        if (raw == null || raw.isJsonNull) return null
        if (raw.isJsonPrimitive) return raw.asString.takeIf(String::isNotEmpty)
        val parts = raw.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val texts = parts.mapNotNull { part -> part.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asString?.takeIf(String::isNotEmpty) }
        return texts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
