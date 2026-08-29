package cn.com.omnimind.bot.ui.channel

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.baselib.mccloud.McCloud
import cn.com.omnimind.baselib.mccloud.McCloudApiException
import cn.com.omnimind.baselib.mccloud.McCloudEvents
import cn.com.omnimind.baselib.mccloud.McCloudWechatState
import cn.com.omnimind.baselib.mccloud.McCloudDomain
import cn.com.omnimind.baselib.mccloud.McCloudStorageUnavailableException
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.MonkeyCodeCloudCredentialLifecycle
import cn.com.omnimind.baselib.llm.MonkeyCodeCloudModelDescriptor
import cn.com.omnimind.baselib.llm.MonkeyCodeCloudProvider
import cn.com.omnimind.baselib.util.OmniLog
import com.alipay.sdk.app.AuthTask
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class McCloudAccountChannel : EventChannel.StreamHandler {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope = newScope()
    private val gson = Gson()
    private var context: Context? = null
    private var activity: Activity? = null
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private var unsubscribe: (() -> Unit)? = null
    private var wechatPolling: Job? = null
    private var pendingPhoneToken: String? = null

    fun onCreate(context: Context) {
        this.context = context.applicationContext
        this.activity = context as? Activity
    }

    fun setChannel(engine: FlutterEngine) {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        methodChannel = MethodChannel(engine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
        eventChannel = EventChannel(engine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        methodChannel?.setMethodCallHandler(::handle)
        eventChannel?.setStreamHandler(this)
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        unsubscribe?.invoke()
        unsubscribe = McCloudEvents.subscribe { payload ->
            mainHandler.post { eventSink?.success(McCloudPayloadSanitizer.sanitize(payload)) }
        }
    }

    override fun onCancel(arguments: Any?) {
        unsubscribe?.invoke()
        unsubscribe = null
        eventSink = null
    }

    fun clear() {
        unsubscribe?.invoke()
        unsubscribe = null
        eventSink = null
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        methodChannel = null
        eventChannel = null
        activity = null
    }

    fun shutdown() {
        wechatPolling?.cancel()
        wechatPolling = null
        runCatching { McCloud.get().oauth.cancelWechatLogin() }
        runCatching { McCloud.get().tasks.shutdown() }
        runCatching { McCloud.get().files.shutdown() }
        pendingPhoneToken = null
        scope.cancel()
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        val service = try {
            McCloud.get()
        } catch (error: Exception) {
            result.error("MC_CLOUD_NOT_INITIALIZED", "MonkeyCode 云服务尚未初始化", null)
            return
        }
        when (call.method) {
            "getSessionState" -> launch(result) {
                if (!service.session.isSignedIn()) {
                    mapOf("signedIn" to false, "user" to null)
                } else {
                    try {
                        mapOf("signedIn" to true, "user" to service.account.status())
                    } catch (error: McCloudApiException) {
                        if (error.statusCode == 401) mapOf("signedIn" to false, "user" to null) else throw error
                    }
                }
            }
            "loginWithPassword" -> launch(result) {
                completeAuthentication(
                    service,
                    service.account.loginWithPassword(
                        call.requiredString("email"),
                        call.requiredString("password", false),
                    ),
                )
            }
            "sendPhoneCode" -> launch(result) {
                service.oauth.sendPhoneCode(call.requiredString("phone"), pendingPhoneToken)
                null
            }
            "loginWithPhone" -> launch(result) {
                completeAuthentication(
                    service,
                    service.oauth.loginPhone(call.requiredString("phone"), call.requiredString("code")),
                )
            }
            "prepareAlipayAppLogin" -> launch(result) { service.oauth.prepareAlipayAppLogin() }
            "loginWithAlipayApp" -> launch(result) {
                val login = service.oauth.loginAlipayApp(call.requiredString("code"), call.requiredString("requestId"))
                pendingPhoneToken = login.pendingPhoneToken
                val user = if (pendingPhoneToken == null) {
                    completeAuthentication(service, service.oauth.bridgeBaizhiSession())
                } else {
                    null
                }
                mapOf("user" to user, "requiresPhoneBind" to (pendingPhoneToken != null))
            }
            "prepareDouyinAppLogin" -> launch(result) {
                mapOf("platform" to "douyin_app", "sdkAvailable" to false, "errorCode" to "THIRD_PARTY_APP_SDK_UNAVAILABLE")
            }
            "loginWithDouyinApp" -> launch(result) {
                val login = service.oauth.loginDouyinApp(call.requiredString("code"))
                pendingPhoneToken = login.pendingPhoneToken
                val user = if (pendingPhoneToken == null) {
                    completeAuthentication(service, service.oauth.bridgeBaizhiSession())
                } else {
                    null
                }
                mapOf("user" to user, "requiresPhoneBind" to (pendingPhoneToken != null))
            }
            "completePhoneBind" -> launch(result) {
                val token = pendingPhoneToken ?: throw IllegalStateException("没有待完成的手机号绑定")
                val user = completeAuthentication(
                    service,
                    service.oauth.completePhoneBindAndBridge(
                        token,
                        call.requiredString("phone"),
                        call.requiredString("code"),
                    ),
                )
                pendingPhoneToken = null
                user
            }
            "loginWithGithub" -> launch(result) { mapOf("url" to service.oauth.getGitHubLoginUrl(call.optionalString("inviterId"))) }
            "getBaizhiGithubLoginUrl" -> launch(result) {
                mapOf("url" to service.oauth.getBaizhiOAuthLoginUrl("github", call.requiredString("redirectUrl")))
            }
            "completeOAuthCallback" -> launch(result) {
                completeAuthentication(service, service.oauth.completeControlledCallback(call.requiredString("url")))
            }
            "completeGithubLogin" -> launch(result) {
                completeAuthentication(service, service.oauth.completeControlledCallback(call.requiredString("callbackUrl")))
                mapOf("completed" to true)
            }
            "importWebSession" -> launch(result) {
                completeAuthentication(service, service.oauth.completeControlledCallback(call.requiredString("url")))
                mapOf("imported" to true)
            }
            "importSessionCookie" -> launch(result) {
                val domain = when (call.requiredString("domain")) {
                    "monkeycode" -> McCloudDomain.MONKEY_CODE
                    else -> throw IllegalArgumentException("domain is invalid")
                }
                service.oauth.importControlledSessionCookie(domain, call.requiredString("cookie", false))
                completeAuthentication(service, service.account.status())
            }
            "getThirdPartyLoginCapabilities" -> launch(result) {
                val alipayAvailable = activity != null && isPackageInstalled(ALIPAY_PACKAGE)
                mapOf(
                    "alipay" to mapOf(
                        "available" to alipayAvailable,
                        "reason" to when {
                            activity == null -> "当前没有可用的 Activity"
                            !alipayAvailable -> "当前设备未安装支付宝"
                            else -> ""
                        },
                    ),
                    "douyin" to mapOf(
                        "available" to false,
                        "reason" to "当前安装包未集成抖音授权 SDK",
                    ),
                )
            }
            "authorizeThirdPartyApp" -> launch(result) {
                when (val platform = call.requiredString("platform")) {
                    "alipay" -> authorizeAlipay(call.authorization())
                    else -> throw McCloudThirdPartySdkUnavailableException(platform)
                }
            }
            "startWechatLogin" -> launch(result) {
                val session = service.oauth.startWechatLogin()
                startWechatPolling()
                mapOf("qrDataUrl" to session.qrDataUrl)
            }
            "cancelWechatLogin" -> launch(result) {
                wechatPolling?.cancel()
                wechatPolling = null
                service.oauth.cancelWechatLogin()
                null
            }
            "logout" -> launch(result) {
                try {
                    revokeCloudCredentials(service)
                } finally {
                    service.account.logout()
                }
                pendingPhoneToken = null
                null
            }
            "deleteAccount" -> launch(result) {
                try {
                    revokeCloudCredentials(service)
                } finally {
                    service.account.deleteAccount()
                }
                pendingPhoneToken = null
                null
            }
            "bindEmail" -> launch(result) { service.account.bindEmail(call.requiredString("email")); null }
            "getWallet" -> launch(result) { service.cloud.getWallet() }
            "getCheckinStatus" -> launch(result) { service.cloud.getCheckinStatus() }
            "submitCheckin" -> launch(result) { service.cloud.checkin(); service.cloud.getDashboard() }
            "listInvitations" -> launch(result) { service.cloud.listInvitations(call.int("page", 1), call.int("size", 50)) }
            "getSubscription" -> launch(result) { service.cloud.getSubscription() }
            "getDashboard" -> launch(result) { service.cloud.getDashboard() }
            "listGitIdentities" -> launch(result) { service.git.list() }
            "getGitIdentity" -> launch(result) { service.git.detail(call.requiredString("id"), call.argument<Boolean>("flush") == true) }
            "getGitOAuthUrl" -> launch(result) { mapOf("url" to service.git.getOAuthUrl(call.requiredString("platform"), call.optionalString("base"))) }
            "addGitIdentity" -> launch(result) { service.git.add(call.fields()) }
            "updateGitIdentity" -> launch(result) { service.git.update(call.requiredString("id"), call.fields()); null }
            "deleteGitIdentity" -> launch(result) { service.git.delete(call.requiredString("id")); null }
            "listModels" -> launch(result) {
                val models = service.models.list()
                syncCloudProfiles(service, models)
                ensureCloudCredentials(service)
                models
            }
            "createModel" -> launch(result) { service.models.create(call.fields()) }
            "updateModel" -> launch(result) { service.models.update(call.requiredString("id"), call.fields()); null }
            "deleteModel" -> launch(result) { service.models.delete(call.requiredString("id")); null }
            "checkModelConfig" -> launch(result) { service.models.healthCheck(call.fields()) }
            "listProviderModels" -> launch(result) { service.models.listProviderModels(call.requiredString("apiKey", false), call.requiredString("baseUrl"), call.requiredString("provider")) }
            "listProjects" -> launch(result) { service.projects.listProjects(call.optionalString("cursor"), call.int("limit", 50)) }
            "createProject" -> launch(result) { service.projects.createProject(call.fields()) }
            "getProjectDetail" -> launch(result) { service.projects.getProjectDetail(call.requiredString("id")) }
            "listTasks" -> launch(result) { service.projects.listTasks(call.int("page", 1), call.int("size", 20), call.optionalString("status"), call.optionalString("projectId")) }
            "getTaskDetail" -> launch(result) { service.projects.getTaskDetail(call.requiredString("id")) }
            "createTask" -> launch(result) { service.projects.createTask(call.fields()) }
            "stopTask" -> launch(result) { service.projects.stopTask(call.requiredString("id")); null }
            "deleteTask" -> launch(result) { service.projects.deleteTask(call.requiredString("id")); null }
            "getTaskOptions" -> launch(result) { service.projects.getTaskOptions() }
            "getTaskRounds" -> launch(result) { service.projects.getTaskRounds(call.requiredString("id"), call.optionalString("cursor"), call.int("limit", 2)) }
            "getTaskUserInputs" -> launch(result) { service.projects.getTaskUserInputs(call.requiredString("id"), call.optionalString("cursor"), call.int("limit", 20)) }
            "openTaskStream" -> launch(result) { mapOf("taskId" to service.tasks.open(call.requiredString("id"), call.optionalString("mode") ?: "stream")) }
            "sendTaskStreamMessage" -> launch(result) { mapOf("sent" to service.tasks.send(call.requiredString("id"), call.requiredString("data", false))) }
            "closeTaskStream" -> launch(result) { service.tasks.close(call.requiredString("id")); null }
            "uploadAttachment" -> launch(result) { service.files.uploadAttachment(call.requiredString("operationId"), call.localFile("sourcePath")) }
            "uploadVmFile" -> launch(result) { service.files.uploadVmFile(call.requiredString("operationId"), call.requiredString("vmId"), call.requiredString("path"), call.localFile("sourcePath")); null }
            "downloadVmFile" -> launch(result) { mapOf("bytes" to service.files.downloadVmFile(call.requiredString("operationId"), call.requiredString("vmId"), call.requiredString("path"), call.requiredString("filename"), call.localDestination("destinationPath"))) }
            "cancelTransfer" -> launch(result) { mapOf("canceled" to service.files.cancel(call.requiredString("operationId"))) }
            "shutdownCloudOperations" -> launch(result) {
                service.oauth.cancelWechatLogin()
                service.tasks.shutdown()
                service.files.shutdown()
                null
            }
            else -> result.notImplemented()
        }
    }

    private fun startWechatPolling() {
        wechatPolling?.cancel()
        wechatPolling = scope.launch {
            try {
                while (true) {
                    val state = withContext(Dispatchers.IO) { McCloud.get().oauth.pollWechatLogin() }
                    McCloudEvents.emit(mapOf("type" to "wechatLoginState", "state" to state.name.lowercase()))
                    if (state == McCloudWechatState.COMPLETED) {
                        val user = withContext(Dispatchers.IO) {
                            val service = McCloud.get()
                            completeAuthentication(service, service.account.status())
                        }
                        McCloudEvents.emit(mapOf("type" to "wechatLoginCompleted", "user" to user.toSafePayload()))
                        break
                    }
                    if (state == McCloudWechatState.CANCELED || state == McCloudWechatState.EXPIRED) break
                    delay(500)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = failureFor(error)
                McCloudEvents.emit(mapOf("type" to "wechatLoginFailed", "code" to failure.first, "message" to failure.second))
            } finally {
                wechatPolling = null
            }
        }
    }

    private fun launch(result: MethodChannel.Result, block: suspend () -> Any?) {
        scope.launch {
            try {
                result.success(withContext(Dispatchers.IO) { block() }.toSafePayload())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = failureFor(error)
                OmniLog.w(TAG, "McCloud channel request failed: ${failure.first}")
                result.error(failure.first, failure.second, failure.third)
            }
        }
    }

    private suspend fun ensureCloudCredentials(service: cn.com.omnimind.baselib.mccloud.McCloudServices) {
        if (!ModelProviderConfigStore.isSecureStorageAvailable()) {
            throw McCloudStorageUnavailableException()
        }
        ModelProviderConfigStore.cloudProfileIds().forEach { profileId ->
            MonkeyCodeCloudCredentialLifecycle.ensure(profileId, service.cloud)
        }
    }

    private suspend fun completeAuthentication(
        service: cn.com.omnimind.baselib.mccloud.McCloudServices,
        user: cn.com.omnimind.baselib.mccloud.McCloudUser,
    ): cn.com.omnimind.baselib.mccloud.McCloudUser {
        try {
            val models = service.models.list()
            syncCloudProfiles(service, models)
            ensureCloudCredentials(service)
        } catch (error: CancellationException) {
            throw error
        } catch (error: McCloudApiException) {
            if (error.statusCode == 401) throw error
            OmniLog.w(TAG, "McCloud post-login model sync failed: ${error.javaClass.simpleName}")
        } catch (error: Exception) {
            OmniLog.w(TAG, "McCloud post-login model sync failed: ${error.javaClass.simpleName}")
        }
        return user
    }

    private suspend fun revokeCloudCredentials(service: cn.com.omnimind.baselib.mccloud.McCloudServices) {
        MonkeyCodeCloudCredentialLifecycle.revokeAll(ModelProviderConfigStore.cloudProfileIds(), service.cloud)
    }

    private suspend fun syncCloudProfiles(
        service: cn.com.omnimind.baselib.mccloud.McCloudServices,
        models: List<cn.com.omnimind.baselib.mccloud.McCloudModel>,
    ) {
        val existing = ModelProviderConfigStore.listProfiles()
        val retained = existing.filterNot { profile ->
            MonkeyCodeCloudProvider.isCloudSource(profile.sourceType)
        }
        val existingCloudById = existing
            .filter { profile -> MonkeyCodeCloudProvider.isCloudSource(profile.sourceType) }
            .associateBy { it.id }
        val cloud = MonkeyCodeCloudProvider.projectModels(
            models.map { model ->
                MonkeyCodeCloudModelDescriptor(
                    id = model.id,
                    model = model.model,
                    displayName = model.remark.orEmpty().ifBlank { model.model },
                    interfaceType = model.interfaceType.orEmpty(),
                    ownerType = model.owner?.type.orEmpty(),
                    hidden = model.isHidden,
                    locked = model.locked,
                )
            },
            MonkeyCodeCloudProvider.DEFAULT_PROXY_BASE_URL,
        ).map { projection ->
            MonkeyCodeCloudProvider.synchronizeProfile(
                profile = projection.profile,
                previous = existingCloudById[projection.profile.id],
            )
        }
        val existingCloudIds = existing.filter { profile ->
            MonkeyCodeCloudProvider.isCloudSource(profile.sourceType)
        }.map { it.id }
        val sharedCredential = ModelProviderConfigStore.firstMonkeyCodeCloudCredential()
        if (cloud.isEmpty()) {
            MonkeyCodeCloudCredentialLifecycle.revokeAll(existingCloudIds, service.cloud)
        }
        ModelProviderConfigStore.replaceProfiles(retained + cloud)
        if (sharedCredential != null) {
            cloud.forEach { profile ->
                ModelProviderConfigStore.writeMonkeyCodeCloudCredential(profile.id, sharedCredential)
            }
        }
    }

    private fun failureFor(error: Exception): Triple<String, String, Map<String, Any?>?> = when (error) {
        is IllegalArgumentException -> Triple("INVALID_ARGUMENT", error.message ?: "参数无效", null)
        is McCloudStorageUnavailableException -> Triple("MC_CLOUD_STORAGE_UNAVAILABLE", error.message.orEmpty(), null)
        is McCloudThirdPartySdkUnavailableException -> Triple(
            "THIRD_PARTY_APP_SDK_UNAVAILABLE",
            "当前安装包未集成${error.platform}授权 SDK",
            mapOf("platform" to error.platform),
        )
        is McCloudApiException -> Triple(
            if (error.statusCode == 401) "MC_CLOUD_UNAUTHENTICATED" else "MC_CLOUD_REQUEST_FAILED",
            McCloudPayloadSanitizer.sanitizeMessage(error.message ?: "MonkeyCode 云请求失败"),
            buildMap {
                error.statusCode?.let { put("statusCode", it) }
                error.errorCode?.let { put("errorCode", it) }
            }.takeIf { it.isNotEmpty() },
        )
        is IllegalStateException -> Triple("MC_CLOUD_STATE_ERROR", error.message ?: "云服务状态异常", null)
        else -> Triple("MC_CLOUD_OPERATION_FAILED", "MonkeyCode 云操作失败", null)
    }

    private fun MethodCall.requiredString(name: String, trim: Boolean = true): String {
        val raw = argument<String>(name).orEmpty()
        return (if (trim) raw.trim() else raw).also { require(it.isNotEmpty()) { "$name is required" } }
    }

    private fun MethodCall.optionalString(name: String): String? = argument<String>(name)?.trim()?.takeIf(String::isNotEmpty)
    private fun MethodCall.int(name: String, default: Int): Int = (argument<Number>(name)?.toInt() ?: default)

    @Suppress("UNCHECKED_CAST")
    private fun MethodCall.fields(): Map<String, Any?> = argument<Map<String, Any?>>("fields") ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun MethodCall.authorization(): Map<String, Any?> =
        argument<Map<String, Any?>>("authorization") ?: emptyMap()

    private fun authorizeAlipay(authorization: Map<String, Any?>): Map<String, Any?> {
        val authInfo = (authorization["authInfo"] ?: authorization["auth_info"])
            ?.toString()?.trim().orEmpty()
        require(authInfo.isNotEmpty()) { "authInfo is required" }
        val host = activity ?: throw IllegalStateException("当前没有可用的 Activity")
        val result = AuthTask(host).authV2(authInfo, true)
        val status = result["resultStatus"].orEmpty()
        val memo = result["memo"].orEmpty()
        val payload = result["result"].orEmpty().trim().trim('"')
        val uri = Uri.parse("https://localhost/?$payload")
        val code = uri.getQueryParameter("auth_code").orEmpty()
        val resultCode = uri.getQueryParameter("result_code").orEmpty()
        if (status == "6001") throw IllegalStateException(memo.ifBlank { "已取消支付宝授权" })
        if (status != "9000" || code.isBlank() || (resultCode.isNotBlank() && resultCode != "200")) {
            throw IllegalStateException(memo.ifBlank { "支付宝授权失败（$status）" })
        }
        return mapOf(
            "code" to code,
            "resultStatus" to status,
            "resultCode" to resultCode,
            "userId" to uri.getQueryParameter("user_id").orEmpty(),
        )
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        val appContext = context ?: return false
        return try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun MethodCall.localFile(name: String): File {
        val file = File(requiredString(name))
        require(file.isFile) { "$name is not a file" }
        return file
    }

    private fun MethodCall.localDestination(name: String): File {
        val file = File(requiredString(name))
        require(file.isAbsolute) { "$name must be absolute" }
        return file
    }

    private fun Any?.toSafePayload(): Any? {
        if (this == null || this is String || this is Number || this is Boolean) return this
        val type = object : TypeToken<Any?>() {}.type
        return McCloudPayloadSanitizer.sanitize(gson.fromJson<Any?>(gson.toJson(this), type))
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val TAG = "McCloudAccountChannel"
        private const val METHOD_CHANNEL = "cn.com.omnimind.bot/McCloudAccount"
        private const val EVENT_CHANNEL = "cn.com.omnimind.bot/McCloudAccountEvents"
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    }
}

private class McCloudThirdPartySdkUnavailableException(
    val platform: String,
) : IllegalStateException("$platform SDK is unavailable")

internal object McCloudPayloadSanitizer {
    private val sensitiveKeys = setOf(
        "apikey", "accesstoken", "signingsecret", "uploadurl", "cookie", "password",
    )

    fun sanitize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.mapNotNull { (key, nested) ->
            val name = key?.toString() ?: return@mapNotNull null
            if (name.lowercase().replace("_", "") in sensitiveKeys) null else name to sanitize(nested)
        }.toMap()
        is Iterable<*> -> value.map(::sanitize)
        is String -> sanitizeJsonString(value)
        else -> value
    }

    private fun sanitizeJsonString(value: String): Any {
        val trimmed = value.trim()
        if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) return value
        return runCatching<Any> {
            val parsed = Gson().fromJson<Any?>(value, object : TypeToken<Any?>() {}.type)
            Gson().toJson(sanitize(parsed))
        }.getOrDefault("{\"type\":\"malformed-cloud-event\"}")
    }

    fun sanitizeMessage(value: String): String = sensitiveAssignment.replace(value, "$1=[REDACTED]")

    private val sensitiveAssignment = Regex(
        "(?i)(api[_-]?key|access[_-]?token|signing[_-]?secret|password)\\s*[:=]\\s*[^\\s,;]+",
    )
}
