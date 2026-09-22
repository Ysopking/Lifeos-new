package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectPreparation
import app.lifeos.core.runtime.policy.OwnerEffectPreparationResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class AppIntentOperation(
    val capabilityValue: String,
    val launchesExternally: Boolean,
) {
    RESOLVE("app.intent.resolve", false),
    LAUNCH("app.intent.launch", true),
    DEEP_LINK_OPEN("app.deeplink.open", true),
    ;

    val capabilityId: CapabilityId
        get() = CapabilityId(capabilityValue)
}

enum class AppIntentAction(
    val androidAction: String,
) {
    VIEW("android.intent.action.VIEW"),
    MAIN("android.intent.action.MAIN"),
    DIAL("android.intent.action.DIAL"),
    SEND_TO("android.intent.action.SENDTO"),
}

sealed interface AppIntentExtra {
    val key: String

    data class Text(
        override val key: String,
        val value: String,
    ) : AppIntentExtra {
        init {
            requireIntentExtraKey(key)
            require(value.length <= MAX_APP_INTENT_EXTRA_TEXT_CHARS)
            require(value.none { it == '\u0000' })
        }
    }

    data class Flag(
        override val key: String,
        val value: Boolean,
    ) : AppIntentExtra {
        init {
            requireIntentExtraKey(key)
        }
    }

    data class Number(
        override val key: String,
        val value: Long,
    ) : AppIntentExtra {
        init {
            requireIntentExtraKey(key)
        }
    }

    fun fingerprint(): String = when (this) {
        is Text -> appIntentFingerprint("app-intent-extra/text/v1", key, value)
        is Flag -> appIntentFingerprint("app-intent-extra/flag/v1", key, value.toString())
        is Number -> appIntentFingerprint("app-intent-extra/number/v1", key, value.toString())
    }
}

data class AppIntentTarget(
    val packageName: String,
    val className: String,
) {
    init {
        requirePackageName(packageName)
        require(className.isNotBlank() && className.length <= MAX_APP_INTENT_CLASS_CHARS)
        require(className.none(Char::isISOControl))
    }

    val policyResource: String
        get() = "android-app://" + packageName + "/" + className

    val executionAuthority: Boolean
        get() = false

    fun fingerprint(): String = appIntentFingerprint(
        "app-intent-target/v1",
        packageName,
        className,
    )
}

data class AppDeepLink private constructor(
    val canonicalUri: String,
    val scheme: String,
) {
    init {
        require(canonicalUri.isNotBlank() && canonicalUri.length <= MAX_APP_DEEP_LINK_CHARS)
        require(scheme.isNotBlank())
    }

    fun fingerprint(): String = appIntentFingerprint(
        "app-deep-link/v1",
        canonicalUri,
        scheme,
    )

    companion object {
        fun create(
            rawUri: String,
            allowedCustomSchemes: Set<String> = emptySet(),
        ): AppDeepLink {
            require(rawUri.isNotBlank() && rawUri.length <= MAX_APP_DEEP_LINK_CHARS)
            require(rawUri.none(Char::isISOControl))
            val parsed = URI(rawUri).normalize()
            val scheme = parsed.scheme?.lowercase()
                ?: throw IllegalArgumentException("Deep link must contain a scheme")
            val canonicalCustom = allowedCustomSchemes
                .map { it.lowercase() }
                .onEach(::requireCustomScheme)
                .toSet()

            require(scheme == "https" || scheme in canonicalCustom) {
                "Unsupported deep-link scheme"
            }
            require(scheme !in FORBIDDEN_DEEP_LINK_SCHEMES) {
                "Forbidden deep-link scheme"
            }

            val canonical = if (scheme == "https") {
                require(parsed.userInfo == null) { "Deep link user-info is not allowed" }
                val host = parsed.host?.lowercase()
                    ?: throw IllegalArgumentException("HTTPS deep link requires a host")
                val port = if (parsed.port == 443) -1 else parsed.port
                URI(
                    "https",
                    null,
                    host,
                    port,
                    parsed.rawPath?.ifBlank { "/" } ?: "/",
                    parsed.rawQuery,
                    parsed.rawFragment,
                ).toASCIIString()
            } else {
                URI(
                    scheme,
                    parsed.rawSchemeSpecificPart,
                    parsed.rawFragment,
                ).toASCIIString()
            }
            require(canonical.length <= MAX_APP_DEEP_LINK_CHARS)
            return AppDeepLink(canonical, scheme)
        }
    }
}

data class AppIntentRequest(
    val operation: AppIntentOperation,
    val action: AppIntentAction,
    val deepLink: AppDeepLink? = null,
    val packageName: String? = null,
    val exactTarget: AppIntentTarget? = null,
    val extras: List<AppIntentExtra> = emptyList(),
) {
    init {
        require(packageName == null || packageName.also(::requirePackageName) == packageName)
        require(extras.size <= MAX_APP_INTENT_EXTRAS)
        require(extras.map { it.key }.distinct().size == extras.size) {
            "Intent extra keys must be unique"
        }
        require(extras == extras.sortedBy { it.key }) {
            "Intent extras must be canonical by key"
        }
        if (exactTarget != null && packageName != null) {
            require(exactTarget.packageName == packageName) {
                "Exact target package does not match requested package"
            }
        }
        when (operation) {
            AppIntentOperation.DEEP_LINK_OPEN -> {
                require(action == AppIntentAction.VIEW)
                require(deepLink != null)
            }
            AppIntentOperation.LAUNCH -> require(deepLink == null) {
                "Generic app launch cannot smuggle a deep link"
            }
            AppIntentOperation.RESOLVE -> Unit
        }
    }

    val executionAuthority: Boolean
        get() = false

    fun fingerprint(): String = appIntentFingerprint(
        "app-intent-request/v1",
        operation.name,
        action.name,
        deepLink?.fingerprint().orEmpty(),
        packageName.orEmpty(),
        exactTarget?.fingerprint().orEmpty(),
        extras.joinToString("\u001f") { it.fingerprint() },
    )

    companion object {
        fun create(
            operation: AppIntentOperation,
            action: AppIntentAction,
            deepLink: AppDeepLink? = null,
            packageName: String? = null,
            exactTarget: AppIntentTarget? = null,
            extras: List<AppIntentExtra> = emptyList(),
        ): AppIntentRequest = AppIntentRequest(
            operation = operation,
            action = action,
            deepLink = deepLink,
            packageName = packageName,
            exactTarget = exactTarget,
            extras = extras.sortedBy { it.key },
        )
    }
}

data class AppIntentResolution(
    val requestFingerprint: String,
    val targets: List<AppIntentTarget>,
    val fingerprint: String,
) {
    init {
        require(requestFingerprint.matches(SHA_256_REGEX))
        require(targets.size <= MAX_APP_INTENT_TARGETS)
        require(targets == targets.distinct().sortedWith(TARGET_ORDER))
        require(
            fingerprint == appIntentFingerprint(
                "app-intent-resolution/v1",
                requestFingerprint,
                targets.joinToString("\u001f") { it.fingerprint() },
            )
        )
    }

    val executionAuthority: Boolean
        get() = false
}

data class AppIntentLaunchReceipt(
    val requestFingerprint: String,
    val target: AppIntentTarget,
    val action: AppIntentAction,
    val canonicalUri: String?,
    val fingerprint: String,
) {
    init {
        require(requestFingerprint.matches(SHA_256_REGEX))
        require(canonicalUri == null || canonicalUri.length <= MAX_APP_DEEP_LINK_CHARS)
        require(
            fingerprint == appIntentFingerprint(
                "app-intent-launch-receipt/v1",
                requestFingerprint,
                target.fingerprint(),
                action.name,
                canonicalUri.orEmpty(),
            )
        )
    }

    companion object {
        fun create(
            request: AppIntentRequest,
            target: AppIntentTarget,
        ): AppIntentLaunchReceipt = AppIntentLaunchReceipt(
            requestFingerprint = request.fingerprint(),
            target = target,
            action = request.action,
            canonicalUri = request.deepLink?.canonicalUri,
            fingerprint = appIntentFingerprint(
                "app-intent-launch-receipt/v1",
                request.fingerprint(),
                target.fingerprint(),
                request.action.name,
                request.deepLink?.canonicalUri.orEmpty(),
            ),
        )
    }
}

interface AppIntentHost {
    suspend fun resolve(request: AppIntentRequest): List<AppIntentTarget>

    suspend fun launch(
        request: AppIntentRequest,
        target: AppIntentTarget,
    ): AppIntentLaunchReceipt
}

sealed interface AppIntentPrepareResult {
    data class Ready(
        val launchPlan: AppIntentLaunchPlan,
        val policyAssessment: OwnerPolicyAssessment,
    ) : AppIntentPrepareResult

    data class Blocked(
        val policyAssessment: OwnerPolicyAssessment,
    ) : AppIntentPrepareResult
}

data class AppIntentLaunchPlan(
    val request: AppIntentRequest,
    val dispatchPlanFingerprint: String,
    val target: AppIntentTarget,
    val resolutionFingerprint: String,
    val ownerPreparation: OwnerEffectPreparation,
    val fingerprint: String,
) {
    init {
        require(request.operation.launchesExternally)
        require(dispatchPlanFingerprint.matches(SHA_256_REGEX))
        require(resolutionFingerprint.matches(SHA_256_REGEX))
        require(
            fingerprint == appIntentFingerprint(
                "app-intent-launch-plan/v1",
                request.fingerprint(),
                dispatchPlanFingerprint,
                target.fingerprint(),
                resolutionFingerprint,
                ownerPreparation.requestFingerprint,
                ownerPreparation.policyRevision.toString(),
                ownerPreparation.grantId.value,
            )
        )
    }
}

sealed interface AppIntentExecutionResult {
    data class Launched(
        val receipt: AppIntentLaunchReceipt,
        val policyAssessment: OwnerPolicyAssessment,
        val fingerprint: String,
    ) : AppIntentExecutionResult

    data class Blocked(
        val policyAssessment: OwnerPolicyAssessment,
    ) : AppIntentExecutionResult
}

/**
 * B408 separates intent syntax, Android target resolution, Owner Policy authority and host launch.
 * Resolution is observation-only. A productive launch must preserve the exact resolved target set
 * from preparation through the immediate pre-exposure re-resolution and then pass the shared JIT
 * OwnerPolicyEffectGate under EXTERNAL_APP_HANDOFF.
 */
class AppIntentRuntime(
    private val host: AppIntentHost,
    private val ownerPolicyGate: OwnerPolicyEffectGate,
) {
    suspend fun resolve(
        plan: AndroidCapabilityDispatchPlan,
        request: AppIntentRequest,
    ): AppIntentResolution {
        requirePlan(plan, AppIntentOperation.RESOLVE)
        require(request.operation == AppIntentOperation.RESOLVE)
        return resolveRequest(request)
    }

    suspend fun prepareLaunch(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: AppIntentRequest,
    ): AppIntentPrepareResult {
        require(request.operation.launchesExternally)
        requirePlan(plan, request.operation)
        val resolution = resolveRequest(request)
        val target = selectExactTarget(request, resolution.targets)
        val ownerRequest = ownerRequest(plan, actorId, request, target)
        return when (val prepared = ownerPolicyGate.prepare(ownerRequest)) {
            is OwnerEffectPreparationResult.Blocked ->
                AppIntentPrepareResult.Blocked(prepared.assessment)
            is OwnerEffectPreparationResult.Ready -> {
                val launchPlan = AppIntentLaunchPlan(
                    request = request,
                    dispatchPlanFingerprint = plan.fingerprint(),
                    target = target,
                    resolutionFingerprint = resolution.fingerprint,
                    ownerPreparation = prepared.preparation,
                    fingerprint = appIntentFingerprint(
                        "app-intent-launch-plan/v1",
                        request.fingerprint(),
                        plan.fingerprint(),
                        target.fingerprint(),
                        resolution.fingerprint,
                        prepared.preparation.requestFingerprint,
                        prepared.preparation.policyRevision.toString(),
                        prepared.preparation.grantId.value,
                    ),
                )
                AppIntentPrepareResult.Ready(launchPlan, prepared.assessment)
            }
        }
    }

    suspend fun launch(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        prepared: AppIntentLaunchPlan,
    ): AppIntentExecutionResult {
        val request = prepared.request
        requirePlan(plan, request.operation)
        require(plan.fingerprint() == prepared.dispatchPlanFingerprint) {
            "Android capability dispatch plan changed after intent preparation"
        }

        val currentResolution = resolveRequest(request)
        require(currentResolution.fingerprint == prepared.resolutionFingerprint) {
            "Intent target set changed after preparation"
        }
        val currentTarget = selectExactTarget(request, currentResolution.targets)
        require(currentTarget == prepared.target) {
            "Intent exact target changed after preparation"
        }

        val ownerRequest = ownerRequest(plan, actorId, request, prepared.target)
        return when (
            val exposure = ownerPolicyGate.expose(
                request = ownerRequest,
                prepared = prepared.ownerPreparation,
            ) {
                host.launch(request, prepared.target)
            }
        ) {
            is OwnerEffectExposureResult.Blocked ->
                AppIntentExecutionResult.Blocked(exposure.assessment)
            is OwnerEffectExposureResult.Exposed -> {
                val receipt = exposure.value
                require(receipt.requestFingerprint == request.fingerprint())
                require(receipt.target == prepared.target)
                require(receipt.action == request.action)
                require(receipt.canonicalUri == request.deepLink?.canonicalUri)
                AppIntentExecutionResult.Launched(
                    receipt = receipt,
                    policyAssessment = exposure.assessment,
                    fingerprint = appIntentFingerprint(
                        "app-intent-execution-result/v1",
                        prepared.fingerprint,
                        receipt.fingerprint,
                        exposure.assessment.decisionId.value,
                        exposure.assessment.policyRevision.toString(),
                    ),
                )
            }
        }
    }

    private suspend fun resolveRequest(
        request: AppIntentRequest,
    ): AppIntentResolution {
        val targets = host.resolve(request)
            .distinct()
            .sortedWith(TARGET_ORDER)
        require(targets.size <= MAX_APP_INTENT_TARGETS) {
            "Intent target resolution exceeded bound"
        }
        request.packageName?.let { exactPackage ->
            require(targets.all { it.packageName == exactPackage }) {
                "Exact package request resolved outside requested package"
            }
        }
        request.exactTarget?.let { exact ->
            require(exact in targets) {
                "Exact intent target is not currently resolvable"
            }
        }
        val requestFingerprint = request.fingerprint()
        return AppIntentResolution(
            requestFingerprint = requestFingerprint,
            targets = targets,
            fingerprint = appIntentFingerprint(
                "app-intent-resolution/v1",
                requestFingerprint,
                targets.joinToString("\u001f") { it.fingerprint() },
            ),
        )
    }

    private fun selectExactTarget(
        request: AppIntentRequest,
        targets: List<AppIntentTarget>,
    ): AppIntentTarget {
        require(targets.isNotEmpty()) { "No resolvable intent target" }
        request.exactTarget?.let { exact ->
            require(exact in targets) { "Requested exact target is unavailable" }
            return exact
        }
        require(targets.size == 1) {
            "Intent target is ambiguous; resolve first and bind an exact target"
        }
        return targets.single()
    }

    private fun requirePlan(
        plan: AndroidCapabilityDispatchPlan,
        operation: AppIntentOperation,
    ) {
        require(plan.capabilityId == operation.capabilityId) {
            "Android capability dispatch plan does not match intent operation"
        }
        if (operation.launchesExternally) {
            require(plan.binding.requiredOwnerEffect == OwnerEffectType.EXTERNAL_APP_HANDOFF) {
                "Productive app intent requires EXTERNAL_APP_HANDOFF metadata"
            }
        } else {
            require(plan.binding.requiredOwnerEffect == null) {
                "Intent resolution cannot ignore an owner-effect requirement"
            }
        }
    }

    private fun ownerRequest(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: AppIntentRequest,
        target: AppIntentTarget,
    ): OwnerEffectRequest = OwnerEffectRequest(
        actorId = actorId,
        effect = OwnerEffectType.EXTERNAL_APP_HANDOFF,
        resource = target.policyResource + "?request=" + request.fingerprint(),
        scope = plan.binding.ownerScope,
        capabilityId = plan.capabilityId,
        providerVersion = plan.binding.providerVersion,
    )
}

private val TARGET_ORDER =
    compareBy<AppIntentTarget>({ it.packageName }, { it.className })

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
private val PACKAGE_NAME_REGEX = Regex("[A-Za-z0-9_.]+")
private val CUSTOM_SCHEME_REGEX = Regex("[a-z][a-z0-9+.-]{0,31}")
private val FORBIDDEN_DEEP_LINK_SCHEMES =
    setOf("file", "content", "javascript", "data", "intent", "http")

private const val MAX_APP_INTENT_EXTRAS = 16
private const val MAX_APP_INTENT_EXTRA_KEY_CHARS = 128
private const val MAX_APP_INTENT_EXTRA_TEXT_CHARS = 2048
private const val MAX_APP_INTENT_CLASS_CHARS = 512
private const val MAX_APP_DEEP_LINK_CHARS = 4096
private const val MAX_APP_INTENT_TARGETS = 32

private fun requireIntentExtraKey(key: String) {
    require(key.isNotBlank() && key.length <= MAX_APP_INTENT_EXTRA_KEY_CHARS)
    require(key.none(Char::isISOControl))
}

private fun requirePackageName(packageName: String) {
    require(packageName.isNotBlank() && packageName.length <= 255)
    require(PACKAGE_NAME_REGEX.matches(packageName)) {
        "Invalid Android package name"
    }
}

private fun requireCustomScheme(scheme: String) {
    require(CUSTOM_SCHEME_REGEX.matches(scheme))
    require(scheme !in FORBIDDEN_DEEP_LINK_SCHEMES)
    require(scheme != "https")
}

private fun appIntentFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(domain, *parts).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
