package cn.com.omnimind.baselib.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object MonkeyCodeCloudCredentialLifecycle {
    private val mutationMutex = Mutex()

    suspend fun ensure(
        profileId: String,
        provisioner: MonkeyCodeCloudCredentialProvisioner,
    ): MonkeyCodeCloudCredential = mutationMutex.withLock {
        ModelProviderConfigStore.readMonkeyCodeCloudCredential(profileId)?.let { return@withLock it }
        ModelProviderConfigStore.firstMonkeyCodeCloudCredential(profileId)?.let { credential ->
            ModelProviderConfigStore.writeMonkeyCodeCloudCredential(profileId, credential)
            return@withLock credential
        }
        provisioner.provision().also { credential ->
            ModelProviderConfigStore.writeMonkeyCodeCloudCredential(profileId, credential)
        }
    }

    suspend fun renew(
        profileId: String,
        provisioner: MonkeyCodeCloudCredentialProvisioner,
    ): MonkeyCodeCloudCredential = mutationMutex.withLock {
        val previous = ModelProviderConfigStore.readMonkeyCodeCloudCredential(profileId)
        val affectedProfiles = previous?.let { credential ->
            ModelProviderConfigStore.cloudProfileIds().filter { id ->
                ModelProviderConfigStore.readMonkeyCodeCloudCredential(id)?.keyId == credential.keyId
            }
        }.orEmpty().ifEmpty { listOf(profileId) }
        previous?.let { provisioner.revoke(it.keyId) }
        provisioner.provision().also { credential ->
            affectedProfiles.forEach { id ->
                ModelProviderConfigStore.writeMonkeyCodeCloudCredential(id, credential)
            }
        }
    }

    suspend fun revoke(
        profileId: String,
        provisioner: MonkeyCodeCloudCredentialProvisioner,
    ) = mutationMutex.withLock {
        revokeLocked(profileId, provisioner)
    }

    suspend fun revokeAndClear(
        profileId: String,
        provisioner: MonkeyCodeCloudCredentialProvisioner,
    ) = mutationMutex.withLock {
        val credential = ModelProviderConfigStore.readMonkeyCodeCloudCredential(profileId) ?: return@withLock
        try {
            provisioner.revoke(credential.keyId)
        } finally {
            ModelProviderConfigStore.clearMonkeyCodeCloudCredential(profileId)
        }
    }

    suspend fun revokeAll(
        profileIds: Collection<String>,
        provisioner: MonkeyCodeCloudCredentialProvisioner,
    ) = mutationMutex.withLock {
        val credentials = profileIds.mapNotNull(ModelProviderConfigStore::readMonkeyCodeCloudCredential)
        var firstFailure: Exception? = null
        credentials.distinctBy { it.keyId }.forEach { credential ->
            try {
                provisioner.revoke(credential.keyId)
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
            }
        }
        profileIds.forEach { profileId ->
            ModelProviderConfigStore.clearMonkeyCodeCloudCredential(profileId)
        }
        firstFailure?.let { throw it }
    }

    suspend fun <T> executeWithOneRenewal(
        profileId: String,
        provisioner: MonkeyCodeCloudCredentialProvisioner,
        shouldRenew: (T) -> Boolean,
        operation: suspend (MonkeyCodeCloudCredential) -> T,
    ): T {
        val first = operation(ensure(profileId, provisioner))
        if (!shouldRenew(first)) return first
        return operation(renew(profileId, provisioner))
    }

    private suspend fun revokeLocked(
        profileId: String,
        provisioner: MonkeyCodeCloudCredentialProvisioner,
    ) {
        val credential = ModelProviderConfigStore.readMonkeyCodeCloudCredential(profileId) ?: return
        provisioner.revoke(credential.keyId)
        ModelProviderConfigStore.clearMonkeyCodeCloudCredential(profileId)
    }
}
