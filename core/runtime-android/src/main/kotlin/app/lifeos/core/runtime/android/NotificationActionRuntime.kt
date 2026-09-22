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
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class NotificationOperation(
    val capabilityValue: String,
    val productive: Boolean,
) {
    OBSERVE("notification.observe", false),
    INVOKE_ACTION("notification.action.invoke", true),
    DISMISS("notification.dismiss", true),
    ;

    val capabilityId: CapabilityId
        get() = CapabilityId(capabilityValue)
}

data class NotificationIdentity(
    val notificationKey: String,
    val packageName: String,
    val postedAtMillis: Long,
    val observationFingerprint: String,
) {
    init {
        require(notificationKey.isNotBlank() && notificationKey.length <= MAX_NOTIFICATION_KEY_CHARS)
        require(notificationKey.none { it == '\u0000' || it == '\n' || it == '\r' })
        require(packageName.isNotBlank() && packageName.length <= MAX_NOTIFICATION_PACKAGE_CHARS)
        require(packageName.none(Char::isISOControl))
        require(postedAtMillis >= 0L)
        require(observationFingerprint.matches(SHA_256_REGEX))
    }

    fun fingerprint(): String = notificationActionFingerprint(
        "notification-identity/v1",
        notificationKey,
        packageName,
        postedAtMillis.toString(),
        observationFingerprint,
    )
}

data class NotificationActionDescriptor(
    val index: Int,
    val label: String,
    val fingerprint: String,
) {
    init {
        require(index in 0 until MAX_NOTIFICATION_ACTIONS)
        require(label.isNotBlank() && label.length <= MAX_NOTIFICATION_ACTION_LABEL_CHARS)
        require(label.none { it == '\u0000' || it == '\n' || it == '\r' })
        require(fingerprint.matches(SHA_256_REGEX))
    }

    companion object {
        fun create(
            identity: NotificationIdentity,
            index: Int,
            label: String,
        ): NotificationActionDescriptor {
            val canonicalLabel = label
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_NOTIFICATION_ACTION_LABEL_CHARS)
            require(canonicalLabel.isNotBlank())
            require(index in 0 until MAX_NOTIFICATION_ACTIONS)
            return NotificationActionDescriptor(
                index = index,
                label = canonicalLabel,
                fingerprint = notificationActionFingerprint(
                    "notification-action-descriptor/v1",
                    identity.fingerprint(),
                    index.toString(),
                    canonicalLabel,
                ),
            )
        }
    }
}

data class NotificationHandleSnapshot(
    val identity: NotificationIdentity,
    val actions: List<NotificationActionDescriptor>,
    val fingerprint: String,
) {
    init {
        require(actions.size <= MAX_NOTIFICATION_ACTIONS)
        require(actions == actions.distinctBy { it.index }.sortedBy { it.index })
        require(actions.map { it.fingerprint }.distinct().size == actions.size)
        require(
            fingerprint == notificationActionFingerprint(
                "notification-handle-snapshot/v1",
                identity.fingerprint(),
                actions.joinToString("\u001f") { it.fingerprint },
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            identity: NotificationIdentity,
            actions: List<NotificationActionDescriptor>,
        ): NotificationHandleSnapshot {
            val canonical = actions.distinctBy { it.index }.sortedBy { it.index }
            require(canonical.size == actions.size) {
                "Notification action indexes must be unique"
            }
            return NotificationHandleSnapshot(
                identity = identity,
                actions = canonical,
                fingerprint = notificationActionFingerprint(
                    "notification-handle-snapshot/v1",
                    identity.fingerprint(),
                    canonical.joinToString("\u001f") { it.fingerprint },
                ),
            )
        }
    }
}

data class NotificationActionRequest(
    val identity: NotificationIdentity,
    val action: NotificationActionDescriptor,
) {
    fun fingerprint(): String = notificationActionFingerprint(
        "notification-action-request/v1",
        identity.fingerprint(),
        action.fingerprint,
    )
}

data class NotificationDismissRequest(
    val identity: NotificationIdentity,
) {
    fun fingerprint(): String = notificationActionFingerprint(
        "notification-dismiss-request/v1",
        identity.fingerprint(),
    )
}

data class NotificationActionReceipt(
    val operation: NotificationOperation,
    val requestFingerprint: String,
    val identity: NotificationIdentity,
    val action: NotificationActionDescriptor?,
    val fingerprint: String,
) {
    init {
        require(operation.productive)
        require(requestFingerprint.matches(SHA_256_REGEX))
        if (operation == NotificationOperation.INVOKE_ACTION) require(action != null)
        if (operation == NotificationOperation.DISMISS) require(action == null)
        require(
            fingerprint == notificationActionFingerprint(
                "notification-action-receipt/v1",
                operation.name,
                requestFingerprint,
                identity.fingerprint(),
                action?.fingerprint.orEmpty(),
            )
        )
    }

    companion object {
        fun invoked(request: NotificationActionRequest): NotificationActionReceipt =
            NotificationActionReceipt(
                operation = NotificationOperation.INVOKE_ACTION,
                requestFingerprint = request.fingerprint(),
                identity = request.identity,
                action = request.action,
                fingerprint = notificationActionFingerprint(
                    "notification-action-receipt/v1",
                    NotificationOperation.INVOKE_ACTION.name,
                    request.fingerprint(),
                    request.identity.fingerprint(),
                    request.action.fingerprint,
                ),
            )

        fun dismissed(request: NotificationDismissRequest): NotificationActionReceipt =
            NotificationActionReceipt(
                operation = NotificationOperation.DISMISS,
                requestFingerprint = request.fingerprint(),
                identity = request.identity,
                action = null,
                fingerprint = notificationActionFingerprint(
                    "notification-action-receipt/v1",
                    NotificationOperation.DISMISS.name,
                    request.fingerprint(),
                    request.identity.fingerprint(),
                    "",
                ),
            )
    }
}

interface NotificationActionHost {
    suspend fun inspect(identity: NotificationIdentity): NotificationHandleSnapshot?

    suspend fun invoke(request: NotificationActionRequest): NotificationActionReceipt

    suspend fun dismiss(request: NotificationDismissRequest): NotificationActionReceipt
}

sealed interface NotificationPreparationResult {
    data class Ready(
        val plan: NotificationExecutionPlan,
        val policyAssessment: OwnerPolicyAssessment,
    ) : NotificationPreparationResult

    data class Blocked(
        val policyAssessment: OwnerPolicyAssessment,
    ) : NotificationPreparationResult
}

data class NotificationExecutionPlan(
    val operation: NotificationOperation,
    val requestFingerprint: String,
    val dispatchPlanFingerprint: String,
    val snapshotFingerprint: String,
    val identity: NotificationIdentity,
    val action: NotificationActionDescriptor?,
    val ownerPreparation: OwnerEffectPreparation,
    val fingerprint: String,
) {
    init {
        require(operation.productive)
        require(requestFingerprint.matches(SHA_256_REGEX))
        require(dispatchPlanFingerprint.matches(SHA_256_REGEX))
        require(snapshotFingerprint.matches(SHA_256_REGEX))
        if (operation == NotificationOperation.INVOKE_ACTION) require(action != null)
        if (operation == NotificationOperation.DISMISS) require(action == null)
        require(
            fingerprint == notificationActionFingerprint(
                "notification-execution-plan/v1",
                operation.name,
                requestFingerprint,
                dispatchPlanFingerprint,
                snapshotFingerprint,
                identity.fingerprint(),
                action?.fingerprint.orEmpty(),
                ownerPreparation.requestFingerprint,
                ownerPreparation.policyRevision.toString(),
                ownerPreparation.grantId.value,
            )
        )
    }
}

sealed interface NotificationExecutionResult {
    data class Exposed(
        val receipt: NotificationActionReceipt,
        val policyAssessment: OwnerPolicyAssessment,
        val fingerprint: String,
    ) : NotificationExecutionResult

    data class Blocked(
        val policyAssessment: OwnerPolicyAssessment,
    ) : NotificationExecutionResult
}

/**
 * B409 keeps durable notification observation distinct from process-local executable handles.
 * Productive invoke/dismiss paths bind the exact live snapshot and re-check both the handle and
 * Owner Policy immediately before the host effect.
 */
class NotificationActionRuntime(
    private val host: NotificationActionHost,
    private val ownerPolicyGate: OwnerPolicyEffectGate,
) {
    suspend fun observe(
        plan: AndroidCapabilityDispatchPlan,
        identity: NotificationIdentity,
    ): NotificationHandleSnapshot? {
        requirePlan(plan, NotificationOperation.OBSERVE)
        return host.inspect(identity)
    }

    suspend fun prepareInvoke(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: NotificationActionRequest,
    ): NotificationPreparationResult {
        requirePlan(plan, NotificationOperation.INVOKE_ACTION)
        val snapshot = requireLiveSnapshot(request.identity)
        require(request.action in snapshot.actions) {
            "Notification action descriptor is not live"
        }
        return prepare(
            operation = NotificationOperation.INVOKE_ACTION,
            plan = plan,
            actorId = actorId,
            requestFingerprint = request.fingerprint(),
            snapshot = snapshot,
            action = request.action,
        )
    }

    suspend fun prepareDismiss(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: NotificationDismissRequest,
    ): NotificationPreparationResult {
        requirePlan(plan, NotificationOperation.DISMISS)
        val snapshot = requireLiveSnapshot(request.identity)
        return prepare(
            operation = NotificationOperation.DISMISS,
            plan = plan,
            actorId = actorId,
            requestFingerprint = request.fingerprint(),
            snapshot = snapshot,
            action = null,
        )
    }

    suspend fun invoke(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        prepared: NotificationExecutionPlan,
    ): NotificationExecutionResult {
        require(prepared.operation == NotificationOperation.INVOKE_ACTION)
        requirePlan(plan, NotificationOperation.INVOKE_ACTION)
        require(plan.fingerprint() == prepared.dispatchPlanFingerprint) {
            "Notification capability plan changed after preparation"
        }
        val action = requireNotNull(prepared.action)
        val snapshot = requireLiveSnapshot(prepared.identity)
        require(snapshot.fingerprint == prepared.snapshotFingerprint) {
            "Notification handle changed after preparation"
        }
        require(action in snapshot.actions) {
            "Prepared notification action is stale"
        }
        val request = NotificationActionRequest(prepared.identity, action)
        require(request.fingerprint() == prepared.requestFingerprint)
        return expose(
            plan = plan,
            actorId = actorId,
            prepared = prepared,
            resource = actionResource(prepared.identity, action),
        ) {
            host.invoke(request)
        }
    }

    suspend fun dismiss(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        prepared: NotificationExecutionPlan,
    ): NotificationExecutionResult {
        require(prepared.operation == NotificationOperation.DISMISS)
        requirePlan(plan, NotificationOperation.DISMISS)
        require(plan.fingerprint() == prepared.dispatchPlanFingerprint) {
            "Notification capability plan changed after preparation"
        }
        val snapshot = requireLiveSnapshot(prepared.identity)
        require(snapshot.fingerprint == prepared.snapshotFingerprint) {
            "Notification handle changed after preparation"
        }
        val request = NotificationDismissRequest(prepared.identity)
        require(request.fingerprint() == prepared.requestFingerprint)
        return expose(
            plan = plan,
            actorId = actorId,
            prepared = prepared,
            resource = dismissResource(prepared.identity),
        ) {
            host.dismiss(request)
        }
    }

    private suspend fun prepare(
        operation: NotificationOperation,
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        requestFingerprint: String,
        snapshot: NotificationHandleSnapshot,
        action: NotificationActionDescriptor?,
    ): NotificationPreparationResult {
        val resource = if (operation == NotificationOperation.INVOKE_ACTION) {
            actionResource(snapshot.identity, requireNotNull(action))
        } else {
            dismissResource(snapshot.identity)
        }
        val ownerRequest = ownerRequest(plan, actorId, resource)
        return when (val prepared = ownerPolicyGate.prepare(ownerRequest)) {
            is OwnerEffectPreparationResult.Blocked ->
                NotificationPreparationResult.Blocked(prepared.assessment)
            is OwnerEffectPreparationResult.Ready -> {
                val executionPlan = NotificationExecutionPlan(
                    operation = operation,
                    requestFingerprint = requestFingerprint,
                    dispatchPlanFingerprint = plan.fingerprint(),
                    snapshotFingerprint = snapshot.fingerprint,
                    identity = snapshot.identity,
                    action = action,
                    ownerPreparation = prepared.preparation,
                    fingerprint = notificationActionFingerprint(
                        "notification-execution-plan/v1",
                        operation.name,
                        requestFingerprint,
                        plan.fingerprint(),
                        snapshot.fingerprint,
                        snapshot.identity.fingerprint(),
                        action?.fingerprint.orEmpty(),
                        prepared.preparation.requestFingerprint,
                        prepared.preparation.policyRevision.toString(),
                        prepared.preparation.grantId.value,
                    ),
                )
                NotificationPreparationResult.Ready(
                    plan = executionPlan,
                    policyAssessment = prepared.assessment,
                )
            }
        }
    }

    private suspend fun expose(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        prepared: NotificationExecutionPlan,
        resource: String,
        effect: suspend () -> NotificationActionReceipt,
    ): NotificationExecutionResult {
        val request = ownerRequest(plan, actorId, resource)
        return when (
            val exposure = ownerPolicyGate.expose(
                request = request,
                prepared = prepared.ownerPreparation,
                effect = effect,
            )
        ) {
            is OwnerEffectExposureResult.Blocked ->
                NotificationExecutionResult.Blocked(exposure.assessment)
            is OwnerEffectExposureResult.Exposed -> {
                val receipt = exposure.value
                require(receipt.operation == prepared.operation)
                require(receipt.requestFingerprint == prepared.requestFingerprint)
                require(receipt.identity == prepared.identity)
                require(receipt.action == prepared.action)
                NotificationExecutionResult.Exposed(
                    receipt = receipt,
                    policyAssessment = exposure.assessment,
                    fingerprint = notificationActionFingerprint(
                        "notification-execution-result/v1",
                        prepared.fingerprint,
                        receipt.fingerprint,
                        exposure.assessment.decisionId.value,
                        exposure.assessment.policyRevision.toString(),
                    ),
                )
            }
        }
    }

    private suspend fun requireLiveSnapshot(
        identity: NotificationIdentity,
    ): NotificationHandleSnapshot =
        requireNotNull(host.inspect(identity)) {
            "Notification handle is unavailable or stale"
        }.also {
            require(it.identity == identity) {
                "Notification host returned another identity"
            }
        }

    private fun requirePlan(
        plan: AndroidCapabilityDispatchPlan,
        operation: NotificationOperation,
    ) {
        require(plan.capabilityId == operation.capabilityId) {
            "Android capability dispatch plan does not match notification operation"
        }
        require(
            plan.binding.permissions.any {
                it.kind == AndroidPermissionKind.SPECIAL_ACCESS &&
                    it.name == NOTIFICATION_LISTENER_ACCESS
            }
        ) {
            "Notification operation requires notification-listener special-access metadata"
        }
        if (operation.productive) {
            require(plan.binding.requiredOwnerEffect == OwnerEffectType.NOTIFICATION_ACTION) {
                "Productive notification operation requires NOTIFICATION_ACTION owner effect"
            }
        } else {
            require(plan.binding.requiredOwnerEffect == null) {
                "Notification observation cannot ignore an owner-effect requirement"
            }
        }
    }

    private fun ownerRequest(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        resource: String,
    ): OwnerEffectRequest = OwnerEffectRequest(
        actorId = actorId,
        effect = OwnerEffectType.NOTIFICATION_ACTION,
        resource = resource,
        scope = plan.binding.ownerScope,
        capabilityId = plan.capabilityId,
        providerVersion = plan.binding.providerVersion,
    )

    private fun actionResource(
        identity: NotificationIdentity,
        action: NotificationActionDescriptor,
    ): String = "android-notification-action:" +
        identity.fingerprint() + ":" + action.fingerprint

    private fun dismissResource(
        identity: NotificationIdentity,
    ): String = "android-notification-dismiss:" + identity.fingerprint()
}

const val NOTIFICATION_LISTENER_ACCESS = "notification-listener"

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")

private const val MAX_NOTIFICATION_KEY_CHARS = 2048
private const val MAX_NOTIFICATION_PACKAGE_CHARS = 255
private const val MAX_NOTIFICATION_ACTIONS = 16
private const val MAX_NOTIFICATION_ACTION_LABEL_CHARS = 256

private fun notificationActionFingerprint(
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
