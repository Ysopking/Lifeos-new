package app.lifeos.next

import android.content.Context
import app.lifeos.core.data.agency.EncryptedExternalEffectReceiptRepository
import app.lifeos.core.data.agency.EncryptedExternalPayloadRepository
import app.lifeos.core.data.policy.EncryptedOwnerObservationPolicyRepository
import app.lifeos.core.data.policy.EncryptedOwnerPolicyRepository
import app.lifeos.core.data.resource.EncryptedResourceBudgetRepository
import app.lifeos.core.data.trace.EncryptedDecisionTraceRepository
import app.lifeos.core.runtime.agency.ExternalEffectRuntimeRegistry
import app.lifeos.core.runtime.agency.ExternalTransportRuntimeRegistry
import app.lifeos.core.runtime.agency.PolicyGatedExternalEffectExecutor
import app.lifeos.core.runtime.capability.GeneratedProviderRestoreAuthority
import app.lifeos.core.runtime.capability.GeneratedProviderRestoreAuthorityRuntimeRegistry
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry
import app.lifeos.core.runtime.self.SelfObservationDecisionTraceRecorder
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.DecisionTraceRuntimeRegistry
import app.lifeos.core.runtime.trace.GoalDecisionTraceRecorder
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRecorder
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRuntimeRegistry
import app.lifeos.core.runtime.trace.SubsystemDecisionTraceRecorder
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import app.lifeos.next.kernel.PrivateOwnerObservationPolicyBaseline
import app.lifeos.next.kernel.PrivateOwnerPolicyBaseline
import app.lifeos.next.kernel.WebDeepSearchRuntime

internal data class SharedAuthorityRuntime(
    val ownerPolicy: OwnerPolicyLedger,
    val ownerObservationPolicy: OwnerObservationPolicyLedger,
    val resourceBudgets: ResourceBudgetCoordinator,
    val decisionTraces: DecisionTraceLedger,
    val selfObservationDecisionTraceRecorder: SelfObservationDecisionTraceRecorder,
    val goalDecisionTraceRecorder: GoalDecisionTraceRecorder,
)

/**
 * Keeps durable owner authority, resource and trace composition out of the process installer.
 * Observation authority and effect authority remain separate ledgers.
 */
internal object SharedAuthorityRuntimeComposition {
    suspend fun install(
        context: Context,
        hardware: HardwareResourceIntelligenceRuntime,
    ): SharedAuthorityRuntime {
        SharedResourceBudgetRuntimeRegistry.install(hardware)

        val ownerPolicy = OwnerPolicyLedger(
            EncryptedOwnerPolicyRepository(context)
        )
        val ownerObservationPolicy = OwnerObservationPolicyLedger(
            EncryptedOwnerObservationPolicyRepository(context)
        )
        ExternalEffectRuntimeRegistry.install(
            PolicyGatedExternalEffectExecutor(
                policyGate = OwnerPolicyEffectGate(ownerPolicy),
                receipts = EncryptedExternalEffectReceiptRepository(context),
                payloads = EncryptedExternalPayloadRepository(context),
                transport = ExternalTransportRuntimeRegistry.transport(),
                observationReconciler = ExternalTransportRuntimeRegistry.reconciler(),
            )
        )

        val resourceBudgets = ResourceBudgetCoordinator(
            EncryptedResourceBudgetRepository(context)
        )
        val decisionTraces = DecisionTraceLedger(
            EncryptedDecisionTraceRepository(context)
        )
        val selfObservationDecisionTraceRecorder =
            SelfObservationDecisionTraceRecorder(decisionTraces)
        val goalDecisionTraceRecorder =
            GoalDecisionTraceRecorder(decisionTraces)

        DecisionTraceRuntimeRegistry.install(
            SubsystemDecisionTraceRecorder(decisionTraces)
        )
        LifecycleDecisionTraceRuntimeRegistry.install(
            LifecycleDecisionTraceRecorder(decisionTraces)
        )

        PrivateOwnerPolicyBaseline.ensure(ownerPolicy)
        PrivateOwnerObservationPolicyBaseline.ensure(ownerObservationPolicy)
        WebDeepSearchRuntime.installPolicy(ownerPolicy)
        GeneratedProviderRestoreAuthorityRuntimeRegistry.install(
            GeneratedProviderRestoreAuthority(
                ownerPolicy = ownerPolicy,
                actorId = PrivateOwnerPolicyBaseline.ownerActorId,
                scope = PrivateOwnerPolicyBaseline.GENERATED_PROVIDER_RESTORE_SCOPE,
            )
        )

        return SharedAuthorityRuntime(
            ownerPolicy = ownerPolicy,
            ownerObservationPolicy = ownerObservationPolicy,
            resourceBudgets = resourceBudgets,
            decisionTraces = decisionTraces,
            selfObservationDecisionTraceRecorder =
                selfObservationDecisionTraceRecorder,
            goalDecisionTraceRecorder = goalDecisionTraceRecorder,
        )
    }
}
