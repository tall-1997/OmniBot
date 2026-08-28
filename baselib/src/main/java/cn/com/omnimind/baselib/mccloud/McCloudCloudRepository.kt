package cn.com.omnimind.baselib.mccloud

import cn.com.omnimind.baselib.llm.MonkeyCodeCloudCredential
import cn.com.omnimind.baselib.llm.MonkeyCodeCloudCredentialProvisioner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class McCloudCloudRepository(
    private val remote: McCloudRemote,
    private val captcha: McCloudCaptchaSolver,
) : MonkeyCodeCloudCredentialProvisioner {
    private data class ProvisionedKey(
        val id: String = "",
        @com.google.gson.annotations.SerializedName("api_key") val apiKey: String = "",
        @com.google.gson.annotations.SerializedName("signing_secret") val signingSecret: String = "",
    )

    override suspend fun provision(): MonkeyCodeCloudCredential {
        val key = remote.call<ProvisionedKey>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/ohmyagent/api-keys",
            "POST",
        )
        return MonkeyCodeCloudCredential(key.id, key.apiKey, key.signingSecret)
    }

    override suspend fun revoke(keyId: String) {
        remote.call<Unit>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/ohmyagent/api-keys/${pathId(keyId)}",
            "DELETE",
        )
    }
    suspend fun getWallet(): McCloudWallet = remote.call(
        McCloudDomain.MONKEY_CODE,
        "/api/v1/users/wallet",
    )

    suspend fun getCheckinStatus(): McCloudCheckinStatus = remote.call(
        McCloudDomain.MONKEY_CODE,
        "/api/v1/users/wallet/checkin",
    )

    suspend fun checkin() {
        val token = captcha.obtainCaptchaToken(McCloudDomain.MONKEY_CODE)
        remote.call<Unit>(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/wallet/checkin",
            "POST",
            mapOf("captcha_token" to token),
        )
    }

    suspend fun listInvitations(page: Int = 1, size: Int = 50): McCloudInvitationPage {
        require(page > 0 && size in 1..100) { "邀请分页参数无效" }
        return remote.call(
            McCloudDomain.MONKEY_CODE,
            "/api/v1/users/invitations",
            query = mapOf("page" to page, "size" to size),
        )
    }

    suspend fun getSubscription(): McCloudSubscription = remote.call(
        McCloudDomain.MONKEY_CODE,
        "/api/v1/users/subscription",
    )

    suspend fun getDashboard(): McCloudDashboard = coroutineScope {
        val calls = listOf(
            "wallet" to asyncResult { getWallet() },
            "checkin" to asyncResult { getCheckinStatus() },
            "invitations" to asyncResult { listInvitations() },
            "subscription" to asyncResult { getSubscription() },
        )
        val results = calls.map { it.second }.awaitAll()
        if (results.all { it.isFailure }) {
            throw results.firstNotNullOf { it.exceptionOrNull() }
        }
        val errors = calls.mapIndexedNotNull { index, (name, _) ->
            results[index].exceptionOrNull()?.let { name to cleanMcCloudMessage(it.message) }
        }.toMap()
        McCloudDashboard(
            wallet = results[0].getOrNull() as? McCloudWallet,
            checkin = results[1].getOrNull() as? McCloudCheckinStatus,
            invitations = results[2].getOrNull() as? McCloudInvitationPage,
            subscription = results[3].getOrNull() as? McCloudSubscription,
            errors = errors,
        )
    }

    private fun <T> kotlinx.coroutines.CoroutineScope.asyncResult(block: suspend () -> T) = async {
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
