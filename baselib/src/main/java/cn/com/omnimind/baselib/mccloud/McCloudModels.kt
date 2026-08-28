package cn.com.omnimind.baselib.mccloud

import com.google.gson.annotations.SerializedName

enum class McCloudDomain {
    MONKEY_CODE,
    BAIZHI,
}

data class McCloudEndpoints(
    val monkeyCode: String = DEFAULT_MONKEY_CODE_URL,
    val baizhi: String = DEFAULT_BAIZHI_URL,
) {
    fun baseUrl(domain: McCloudDomain): String = when (domain) {
        McCloudDomain.MONKEY_CODE -> monkeyCode
        McCloudDomain.BAIZHI -> baizhi
    }

    companion object {
        const val DEFAULT_MONKEY_CODE_URL = "https://monkeycode-ai.com"
        const val DEFAULT_BAIZHI_URL = "https://baizhi.cloud"
    }
}

data class McCloudApiEnvelope<T>(
    val code: Int? = null,
    val message: String? = null,
    val data: T? = null,
)

class McCloudApiException(
    val statusCode: Int? = null,
    val errorCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class McCloudStorageUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException("MonkeyCode 安全存储不可用", cause)

data class McCloudUser(
    val id: String = "",
    val name: String = "",
    val username: String = "",
    val email: String = "",
    @SerializedName("avatar_url") val avatarUrl: String = "",
    val role: String = "",
    val team: McCloudTeam? = null,
)

data class McCloudTeam(val id: String = "", val name: String = "")

data class McCloudWallet(
    val balance: Long = 0,
    @SerializedName("daily_token_balance") val dailyTokenBalance: Long = 0,
    @SerializedName("daily_token_limit") val dailyTokenLimit: Long = 0,
) {
    val credits: Long
        get() = balance / 1_000
}

data class McCloudSubscription(
    val plan: String = "basic",
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("auto_renew") val autoRenew: Boolean = false,
    val source: String? = null,
)

data class McCloudInvitation(
    val id: String = "",
    val name: String = "",
    @SerializedName("avatar_url") val avatarUrl: String = "",
    val credits: Long = 0,
    @SerializedName("invited_at") val invitedAt: String? = null,
)

data class McCloudInvitationPage(
    val count: Int = 0,
    val items: List<McCloudInvitation> = emptyList(),
)

data class McCloudCheckinStatus(
    @SerializedName("checked_in") val checkedIn: Boolean = false,
)

data class McCloudOwner(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
)

data class McCloudModel(
    val id: String = "",
    val model: String = "",
    val remark: String? = null,
    val provider: String = "",
    @SerializedName("is_default") val isDefault: Boolean = false,
    @SerializedName("is_free") val isFree: Boolean = false,
    @SerializedName("is_hidden") val isHidden: Boolean = false,
    val locked: Boolean = false,
    val owner: McCloudOwner? = null,
    @SerializedName("base_url") val baseUrl: String? = null,
    @SerializedName("api_key") val apiKey: String? = null,
    @SerializedName("interface_type") val interfaceType: String? = null,
    @SerializedName("context_limit") val contextLimit: Int? = null,
    @SerializedName("output_limit") val outputLimit: Int? = null,
    @SerializedName("thinking_enabled") val thinkingEnabled: Boolean = false,
    @SerializedName("support_image") val supportImage: Boolean = false,
)

data class McCloudModelsResponse(
    val models: List<McCloudModel> = emptyList(),
    @SerializedName("task_defaults") val taskDefaults: Map<String, Any?>? = null,
)

data class McCloudModelHealth(val success: Boolean = false, val error: String? = null)
data class McCloudProviderModel(val model: String = "")
data class McCloudProviderModels(val models: List<McCloudProviderModel> = emptyList())

data class McCloudImage(
    val id: String = "",
    val name: String = "",
    val remark: String? = null,
    @SerializedName("is_default") val isDefault: Boolean = false,
    val owner: McCloudOwner? = null,
)

data class McCloudImagesResponse(val images: List<McCloudImage> = emptyList())

data class McCloudHost(
    val id: String = "",
    val name: String = "",
    val remark: String? = null,
    @SerializedName("external_ip") val externalIp: String? = null,
    val status: String? = null,
    @SerializedName("is_default") val isDefault: Boolean = false,
    val owner: McCloudOwner? = null,
)

data class McCloudHostsResponse(val hosts: List<McCloudHost> = emptyList())

data class McCloudTaskOptions(
    val models: List<McCloudModel>,
    val images: List<McCloudImage>,
    val hosts: List<McCloudHost>,
    val projects: List<McCloudProject>,
    val plan: String,
    val taskDefaults: Map<String, Any?>? = null,
    val optionalErrors: Map<String, String> = emptyMap(),
)

data class McCloudAuthorizedRepository(
    val url: String = "",
    @SerializedName("full_name") val fullName: String = "",
    val description: String? = null,
)

data class McCloudGitIdentity(
    val id: String = "",
    val platform: String = "",
    @SerializedName("base_url") val baseUrl: String = "",
    val username: String = "",
    val email: String = "",
    @SerializedName("access_token") val accessToken: String? = null,
    val remark: String? = null,
    @SerializedName("organization_id") val organizationId: String? = null,
    @SerializedName("is_installation_app") val isInstallationApp: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("authorized_repositories")
    val authorizedRepositories: List<McCloudAuthorizedRepository> = emptyList(),
)

data class McCloudProject(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    @SerializedName("full_name") val fullName: String? = null,
    @SerializedName("repo_url") val repoUrl: String? = null,
    val platform: String? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    val tasks: List<McCloudTask> = emptyList(),
)

data class McCloudTaskStats(
    @SerializedName("input_tokens") val inputTokens: Long = 0,
    @SerializedName("output_tokens") val outputTokens: Long = 0,
    @SerializedName("total_tokens") val totalTokens: Long = 0,
    @SerializedName("llm_requests") val llmRequests: Long = 0,
)

data class McCloudTask(
    val id: String = "",
    val title: String? = null,
    val content: String? = null,
    val summary: String? = null,
    val status: String? = null,
    val type: String? = null,
    @SerializedName("cli_name") val cliName: String? = null,
    @SerializedName("repo_url") val repoUrl: String? = null,
    val branch: String? = null,
    val model: McCloudModel? = null,
    val stats: McCloudTaskStats? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    @SerializedName("completed_at") val completedAt: Long? = null,
)

data class McCloudPageInfo(
    val page: Int = 0,
    val size: Int = 0,
    val total: Int = 0,
    @SerializedName("total_count") val totalCount: Int = 0,
    @SerializedName("has_next_page") val hasNextPage: Boolean = false,
)

data class McCloudTaskPage(
    val tasks: List<McCloudTask> = emptyList(),
    @SerializedName("page_info") val pageInfo: McCloudPageInfo = McCloudPageInfo(),
)

data class McCloudCursorPage(
    val cursor: String? = null,
    @SerializedName("has_more") val hasMore: Boolean = false,
)

data class McCloudProjectPage(
    val projects: List<McCloudProject> = emptyList(),
    val page: McCloudCursorPage = McCloudCursorPage(),
)

data class McCloudTaskChunk(
    val event: String = "",
    val kind: String = "",
    val data: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val timestamp: Long = 0,
    val seq: Long = 0,
    @SerializedName("turn_seq") val turnSeq: Int = 0,
)

data class McCloudTaskRounds(
    val chunks: List<McCloudTaskChunk> = emptyList(),
    @SerializedName("has_more") val hasMore: Boolean = false,
    @SerializedName("next_cursor") val nextCursor: String? = null,
)

data class McCloudTaskUserInput(
    val id: String = "",
    val content: String = "",
    val truncated: Boolean = false,
    val timestamp: Long = 0,
    val seq: Int = 0,
)

data class McCloudTaskUserInputs(
    val items: List<McCloudTaskUserInput> = emptyList(),
    @SerializedName("has_more") val hasMore: Boolean = false,
    @SerializedName("next_cursor") val nextCursor: String? = null,
)

data class McCloudDashboard(
    val wallet: McCloudWallet? = null,
    val checkin: McCloudCheckinStatus? = null,
    val invitations: McCloudInvitationPage? = null,
    val subscription: McCloudSubscription? = null,
    val errors: Map<String, String> = emptyMap(),
)

internal fun cleanMcCloudMessage(message: String?): String {
    val value = message.orEmpty().trim()
    if (value.isEmpty()) return "MonkeyCode cloud request failed"
    return value.replace(TRACE_ID_SUFFIX, "").trim()
}

private val TRACE_ID_SUFFIX = Regex("\\s*\\[trace[_ -]?id[:=][^]]+]\\s*$", RegexOption.IGNORE_CASE)
