package cn.com.omnimind.baselib.mccloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Type

class McCloudRepositoriesTest {
    @Test
    fun modelListUsesLimitAndFiltersHiddenOrIncompleteEntries() = runBlocking {
        val remote = ScriptedRemote().apply {
            respond("/api/v1/users/models", McCloudModelsResponse(models = listOf(
                McCloudModel(id = "visible", model = "m1"),
                McCloudModel(id = "hidden", model = "m2", isHidden = true),
                McCloudModel(id = "", model = "placeholder"),
            )))
        }

        val models = McCloudModelRepository(remote).list()

        assertEquals(listOf("visible"), models.map { it.id })
        assertEquals(200, remote.requests.single().query["limit"])
    }

    @Test
    fun dashboardKeepsSuccessfulBranchesAndReportsPartialFailures() = runBlocking {
        val remote = ScriptedRemote().apply {
            respond("/api/v1/users/wallet", McCloudWallet(balance = 12_000))
            fail("/api/v1/users/wallet/checkin", McCloudApiException(message = "checkin failed"))
            respond("/api/v1/users/invitations", McCloudInvitationPage(count = 2))
            respond("/api/v1/users/subscription", McCloudSubscription(plan = "pro"))
        }
        val repository = McCloudCloudRepository(remote, McCloudCaptchaSolver(remote))

        val dashboard = repository.getDashboard()

        assertEquals(12L, dashboard.wallet?.credits)
        assertNull(dashboard.checkin)
        assertEquals("checkin failed", dashboard.errors["checkin"])
    }

    @Test
    fun dashboardPropagatesUnauthorizedFromAnyBranch() = runBlocking {
        val remote = ScriptedRemote().apply {
            respond("/api/v1/users/wallet", McCloudWallet(balance = 12_000))
            fail("/api/v1/users/wallet/checkin", McCloudApiException(statusCode = 401, message = "expired"))
            respond("/api/v1/users/invitations", McCloudInvitationPage())
            respond("/api/v1/users/subscription", McCloudSubscription())
        }

        val error = runCatching { McCloudCloudRepository(remote, McCloudCaptchaSolver(remote)).getDashboard() }
            .exceptionOrNull()

        assertTrue(error is McCloudApiException)
        assertEquals(401, (error as McCloudApiException).statusCode)
    }

    @Test
    fun taskOptionsRequireModelsAndImagesAndTolerateOptionalFailures() = runBlocking {
        val remote = ScriptedRemote().apply {
            respond("/api/v1/users/models", McCloudModelsResponse(listOf(McCloudModel("m", "model"))))
            respond("/api/v1/users/images", McCloudImagesResponse(listOf(McCloudImage("i", "image"))))
            fail("/api/v1/users/hosts", McCloudApiException(message = "hosts unavailable"))
            respond("/api/v1/users/projects", McCloudProjectPage())
            respond("/api/v1/users/subscription", McCloudSubscription(plan = "basic"))
        }

        val options = McCloudProjectRepository(remote).getTaskOptions()

        assertEquals("m", options.models.single().id)
        assertTrue(options.hosts.isEmpty())
        assertEquals("hosts unavailable", options.optionalErrors["hosts"])
    }

    @Test
    fun gitAuthorizeUrlUsesSourceGetContract() = runBlocking {
        val remote = ScriptedRemote().apply {
            respond("/api/v1/gitlab/authorize_url", McCloudOAuthUrl("https://gitlab.example/oauth"))
        }

        McCloudGitRepository(remote).getOAuthUrl("gitlab", "https://gitlab.example")

        assertEquals("GET", remote.requests.single().method)
        assertEquals("https://gitlab.example", remote.requests.single().query["base"])
    }
}

private data class RecordedRequest(val path: String, val method: String, val query: Map<String, Any?>)

private class ScriptedRemote : McCloudRemote {
    private val responses = mutableMapOf<String, Any?>()
    private val failures = mutableMapOf<String, Throwable>()
    val requests = mutableListOf<RecordedRequest>()

    fun respond(path: String, value: Any?) { responses[path] = value }
    fun fail(path: String, error: Throwable) { failures[path] = error }

    override suspend fun <T> request(
        domain: McCloudDomain,
        path: String,
        method: String,
        body: Any?,
        query: Map<String, Any?>,
        type: Type,
    ): T {
        requests += RecordedRequest(path, method, query)
        failures[path]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return responses[path] as T
    }
}
