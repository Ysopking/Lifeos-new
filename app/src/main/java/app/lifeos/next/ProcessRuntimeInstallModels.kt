package app.lifeos.next

import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatusReader
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntime
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.self.SelfObservationDecisionTraceRecorder
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.GoalDecisionTraceRecorder
import app.lifeos.next.kernel.CanonicalLifePhotonRepository
import app.lifeos.next.kernel.CanonicalPhotonIngress
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.kernel.MultimodalPerceptionRuntime
import app.lifeos.next.kernel.PrivateEscalationRuntime
import app.lifeos.next.kernel.PrivateSelfHealingRuntime

internal data class ProcessRuntimeCriticalInstallResult(
    val kernel: LifeOsKernel,
    val photonIngress: CanonicalPhotonIngress,
    val generatedToolStatusReader: GeneratedToolRuntimeStatusReader,
    val hardwareResourceIntelligence: HardwareResourceIntelligenceRuntime,
    val storageIntelligence: AndroidStorageIntelligenceRuntime,
    val storageMaintenance: AndroidStorageMaintenanceRuntime,
    val storageIntelligenceController: StorageIntelligenceProcessController,
    val ownerPolicy: OwnerPolicyLedger,
    val ownerObservationPolicy: OwnerObservationPolicyLedger,
    val resourceBudgets: ResourceBudgetCoordinator,
    val decisionTraces: DecisionTraceLedger,
    val selfObservationDecisionTraceRecorder: SelfObservationDecisionTraceRecorder,
    val goalDecisionTraceRecorder: GoalDecisionTraceRecorder,
    val lifePhotonRepository: CanonicalLifePhotonRepository,
    val lifeMemoryRuntime: DurableLifeMemoryRuntime,
    val multimodalPerception: MultimodalPerceptionRuntime,
)

internal data class ProcessRuntimeWarmInstallResult(
    val selfHealingRuntime: PrivateSelfHealingRuntime?,
    val escalationRuntime: PrivateEscalationRuntime?,
    val startupReport: LifeOsWarmStartupReport,
)

internal data class ProcessRuntimeInstallResult(
    val critical: ProcessRuntimeCriticalInstallResult,
    val warm: ProcessRuntimeWarmInstallResult,
)
