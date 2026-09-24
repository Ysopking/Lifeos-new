package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityId

data class CrossAppWorkflowStep(
    val stepId: String,
    val capabilityId: CapabilityId,
    val requiredOutputs: Set<String>,
    val resource: String,
    val scope: String,
    val providerId: String? = null,
) {
    init {
        require(stepId.isNotBlank())
        require(requiredOutputs.none { it.isBlank() })
        require(resource.isNotBlank())
        require(scope.isNotBlank())
        require(providerId == null || providerId.isNotBlank())
    }
}

data class CrossAppWorkflowDefinition(
    val workflowId: String,
    val initialInputs: Set<String>,
    val steps: List<CrossAppWorkflowStep>,
) {
    init {
        require(workflowId.isNotBlank())
        require(initialInputs.none { it.isBlank() })
        require(steps.isNotEmpty())
        require(steps.map { it.stepId }.distinct().size == steps.size) {
            "Cross-app workflow step ids must be unique"
        }
    }
}

data class CrossAppWorkflowPlan(
    val workflowId: String,
    val steps: List<Pair<String, AndroidCapabilityDispatchPlan>>,
    val resultingInputs: Set<String>,
) {
    init {
        require(workflowId.isNotBlank())
        require(steps.isNotEmpty())
        require(steps.map { it.first }.distinct().size == steps.size)
        require(resultingInputs.none { it.isBlank() })
    }

    val executionAuthority: Boolean
        get() = false
    val ownerPolicyAuthority: Boolean
        get() = false

    fun fingerprint(): String = androidCapabilityFingerprint(
        "cross-app-workflow-plan/v1",
        workflowId,
        steps.joinToString("\u001f") { (stepId, plan) ->
            "$stepId:${plan.fingerprint()}"
        },
        resultingInputs.sorted().joinToString("\u001f"),
    )
}

sealed interface CrossAppWorkflowResolution {
    data class Ready(
        val plan: CrossAppWorkflowPlan,
    ) : CrossAppWorkflowResolution

    data class Blocked(
        val workflowId: String,
        val stepId: String,
        val reason: AndroidCapabilityResolution,
        val resolvedStepIds: List<String>,
    ) : CrossAppWorkflowResolution {
        init {
            require(workflowId.isNotBlank())
            require(stepId.isNotBlank())
            require(resolvedStepIds == resolvedStepIds.distinct())
        }
    }
}

/**
 * B464D semantic cross-app routing over the existing AndroidCapabilityBus.
 *
 * The router plans only. Each next step receives only the declared output contracts of previously
 * resolved providers. No Android API call or external effect is performed here.
 */
class CrossAppWorkflowRouter(
    private val capabilityBus: AndroidCapabilityBus,
) {
    suspend fun resolve(
        workflow: CrossAppWorkflowDefinition,
        permissions: AndroidPermissionSnapshot,
    ): CrossAppWorkflowResolution {
        val availableInputs = workflow.initialInputs.toMutableSet()
        val resolved = mutableListOf<Pair<String, AndroidCapabilityDispatchPlan>>()

        workflow.steps.forEach { step ->
            val resolution = capabilityBus.resolve(
                request = AndroidCapabilityRequest(
                    capabilityId = step.capabilityId,
                    availableInputs = availableInputs.toSet(),
                    requiredOutputs = step.requiredOutputs,
                    resource = step.resource,
                    scope = step.scope,
                    providerId = step.providerId,
                ),
                permissions = permissions,
            )

            when (resolution) {
                is AndroidCapabilityResolution.Ready -> {
                    resolved += step.stepId to resolution.plan
                    availableInputs +=
                        resolution.plan.binding.descriptor.contract.outputs
                }

                is AndroidCapabilityResolution.CapabilityUnavailable,
                is AndroidCapabilityResolution.ContractMismatch,
                is AndroidCapabilityResolution.PermissionsMissing,
                -> return CrossAppWorkflowResolution.Blocked(
                    workflowId = workflow.workflowId,
                    stepId = step.stepId,
                    reason = resolution,
                    resolvedStepIds = resolved.map { it.first },
                )
            }
        }

        return CrossAppWorkflowResolution.Ready(
            CrossAppWorkflowPlan(
                workflowId = workflow.workflowId,
                steps = resolved.toList(),
                resultingInputs = availableInputs.toSortedSet(),
            )
        )
    }
}
