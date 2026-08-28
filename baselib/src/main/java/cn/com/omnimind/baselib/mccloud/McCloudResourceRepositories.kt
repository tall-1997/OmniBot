package cn.com.omnimind.baselib.mccloud

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class McCloudGitRepository(private val remote: McCloudRemote) {
    suspend fun list(): List<McCloudGitIdentity> =
        remote.call<List<McCloudGitIdentity>>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/git-identities",
        ).filterNot { it.platform == "internal" }

    suspend fun detail(id: String, flush: Boolean = false): McCloudGitIdentity = remote.call(
        McCloudDomain.MONKEY_CODE,
        "/api/v1/users/git-identities/${pathId(id)}",
        query = mapOf("flush" to flush),
    )

    suspend fun add(fields: Map<String, Any?>): McCloudGitIdentity {
        requireFields(fields, "platform", "base_url", "access_token", "username", "email")
        return remote.call(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/git-identities",
            "POST",
            fields,
        )
    }

    suspend fun update(id: String, fields: Map<String, Any?>) {
        remote.call<Unit>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/git-identities/${pathId(id)}",
            "PUT",
            fields,
        )
    }

    suspend fun delete(id: String) {
        remote.call<Unit>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/git-identities/${pathId(id)}",
            "DELETE",
        )
    }

    suspend fun getOAuthUrl(platform: String, base: String? = null): String {
        require(PLATFORM_REGEX.matches(platform)) { "Git 平台无效" }
        require(platform in OAUTH_PLATFORMS) { "Git OAuth 平台无效" }
        val url = remote.call<McCloudOAuthUrl>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/$platform/authorize_url",
            query = mapOf("base" to base),
        ).url
        require(isAllowedAuthorizeUrl(platform, base, url.toHttpUrlOrNull())) { "Git 授权地址域名无效" }
        return url
    }

    private fun isAllowedAuthorizeUrl(platform: String, base: String?, url: HttpUrl?): Boolean {
        if (url == null || !url.isHttps) return false
        val expected = when (platform) {
            "github" -> "https://github.com".toHttpUrlOrNull()
            "gitee" -> "https://gitee.com".toHttpUrlOrNull()
            "gitlab" -> base?.toHttpUrlOrNull() ?: "https://gitlab.com".toHttpUrlOrNull()
            "gitea" -> base?.toHttpUrlOrNull()
            else -> null
        } ?: return false
        return url.host == expected.host && url.port == expected.port
    }

    private companion object {
        val OAUTH_PLATFORMS = setOf("github", "gitee", "gitlab", "gitea")
    }
}

class McCloudModelRepository(private val remote: McCloudRemote) {
    suspend fun list(): List<McCloudModel> = remote.call<McCloudModelsResponse>(
        McCloudDomain.MONKEY_CODE,
        "/api/v1/users/models",
        query = mapOf("limit" to 200),
    ).models.filter { !it.isHidden && it.id.isNotBlank() && it.model.isNotBlank() }

    suspend fun create(fields: Map<String, Any?>): McCloudModel {
        validateModel(fields)
        return remote.call(McCloudDomain.MONKEY_CODE, "/api/v1/users/models", "POST", fields)
    }

    suspend fun update(id: String, fields: Map<String, Any?>) {
        remote.call<Unit>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/models/${pathId(id)}",
            "PUT",
            fields,
        )
    }

    suspend fun delete(id: String) {
        remote.call<Unit>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/models/${pathId(id)}",
            "DELETE",
        )
    }

    suspend fun healthCheck(fields: Map<String, Any?>): McCloudModelHealth {
        validateModel(fields)
        return remote.call(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/models/health-check",
            "POST",
            fields,
        )
    }

    suspend fun listProviderModels(apiKey: String, baseUrl: String, provider: String): List<McCloudProviderModel> {
        require(apiKey.isNotBlank() && baseUrl.isNotBlank() && provider.isNotBlank()) { "模型供应商参数不完整" }
        val result = remote.call<McCloudProviderModels>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/models/providers",
            query = mapOf("api_key" to apiKey, "base_url" to baseUrl, "provider" to provider),
        )
        return result.models
    }

    private fun validateModel(fields: Map<String, Any?>) =
        requireFields(fields, "provider", "model", "base_url", "api_key", "interface_type")
}

class McCloudProjectRepository(private val remote: McCloudRemote) {
    suspend fun listProjects(cursor: String? = null, limit: Int = 50): McCloudProjectPage {
        require(limit in 1..100) { "项目分页大小无效" }
        return remote.call(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/projects",
            query = mapOf("cursor" to cursor, "limit" to limit),
        )
    }

    suspend fun createProject(fields: Map<String, Any?>): McCloudProject = remote.call(
        McCloudDomain.MONKEY_CODE,
        "/api/v1/users/projects",
        "POST",
        fields,
    )

    suspend fun getProjectDetail(id: String): McCloudProject = remote.call(
        McCloudDomain.MONKEY_CODE,
        "/api/v1/users/projects/${pathId(id)}",
    )

    suspend fun listTasks(
        page: Int = 1,
        size: Int = 20,
        status: String? = null,
        projectId: String? = null,
    ): McCloudTaskPage {
        require(page > 0 && size in 1..100) { "任务分页参数无效" }
        return remote.call(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/tasks",
            query = mapOf("page" to page, "size" to size, "status" to status, "project_id" to projectId),
        )
    }

    suspend fun getTaskCount(projectId: String? = null, status: String? = null): Int {
        val page = listTasks(1, 1, status, projectId)
        return page.pageInfo.totalCount.takeIf { it > 0 } ?: page.pageInfo.total.takeIf { it > 0 } ?: page.tasks.size
    }

    suspend fun getTaskDetail(id: String): McCloudTask = remote.call(
        McCloudDomain.MONKEY_CODE,
        "/api/v1/users/tasks/${pathId(id)}",
    )

    suspend fun createTask(fields: Map<String, Any?>): McCloudTask {
        requireFields(fields, "content", "model_id", "host_id", "image_id", "cli_name", "resource")
        return remote.call(McCloudDomain.MONKEY_CODE, "/api/v1/users/tasks", "POST", fields)
    }

    suspend fun stopTask(id: String) {
        remote.call<Unit>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/tasks/stop",
            "PUT",
            mapOf("id" to pathId(id)),
        )
    }

    suspend fun deleteTask(id: String) {
        remote.call<Unit>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/tasks/${pathId(id)}",
            "DELETE",
        )
    }

    suspend fun getTaskRounds(id: String, cursor: String? = null, limit: Int = 2): McCloudTaskRounds {
        require(limit in 1..10) { "轮次分页大小无效" }
        return remote.call<McCloudTaskRounds>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/tasks/rounds",
            query = mapOf("id" to pathId(id), "cursor" to cursor, "limit" to limit),
        ).let { page -> page.copy(chunks = page.chunks.map { it.copy(timestamp = normalizeCloudTimestamp(it.timestamp)) }) }
    }

    suspend fun getTaskUserInputs(id: String, cursor: String? = null, limit: Int = 20): McCloudTaskUserInputs {
        require(limit in 1..100) { "提问分页大小无效" }
        return remote.call<McCloudTaskUserInputs>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/tasks/user-inputs",
            query = mapOf("id" to pathId(id), "cursor" to cursor, "limit" to limit),
        ).let { page -> page.copy(items = page.items.map { it.copy(timestamp = normalizeCloudTimestamp(it.timestamp)) }) }
    }

    suspend fun getTaskOptions(): McCloudTaskOptions {
        val models = remote.call<McCloudModelsResponse>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/models",
            query = mapOf("limit" to 200),
        )
        val images = remote.call<McCloudImagesResponse>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/images",
        )
        val errors = linkedMapOf<String, String>()
        val hosts = optional("hosts", errors, McCloudHostsResponse()) {
            remote.call<McCloudHostsResponse>(McCloudDomain.MONKEY_CODE, "/api/v1/users/hosts")
        }
        val projects = optional("projects", errors, McCloudProjectPage()) { listProjects(limit = 50) }
        val subscription = optional("subscription", errors, McCloudSubscription()) {
            remote.call<McCloudSubscription>(McCloudDomain.MONKEY_CODE, "/api/v1/users/subscription")
        }
        return McCloudTaskOptions(
            models.models.filter { !it.isHidden && it.id.isNotBlank() && it.model.isNotBlank() },
            images.images,
            hosts.hosts,
            projects.projects,
            subscription.plan,
            models.taskDefaults,
            errors,
        )
    }

    private suspend fun <T> optional(
        name: String,
        errors: MutableMap<String, String>,
        fallback: T,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Throwable) {
        errors[name] = cleanMcCloudMessage(error.message)
        fallback
    }
}

private val PLATFORM_REGEX = Regex("^[a-z][a-z0-9_-]{0,31}$")

internal fun pathId(value: String): String {
    val id = value.trim()
    require(id.isNotEmpty() && id.length <= 128 && id.none { it == '/' || it == '?' || it == '#' }) { "资源 ID 无效" }
    return id
}

private fun requireFields(fields: Map<String, Any?>, vararg names: String) {
    require(names.all { fields[it]?.toString()?.isNotBlank() == true }) { "缺少必填字段" }
}

private fun normalizeCloudTimestamp(value: Long): Long = if (value > 10_000_000_000_000L) value / 1_000_000 else value
