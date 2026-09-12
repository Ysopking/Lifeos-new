package app.lifeos.core.runtime.resource

import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.DecisionTraceLink
import app.lifeos.core.runtime.trace.DecisionTraceLinkType
import app.lifeos.core.runtime.trace.DecisionTraceNode
import app.lifeos.core.runtime.trace.DecisionTraceNodeType
import java.time.Instant

/**
 * V16 -> V15 bridge. This recorder is observational only and cannot reserve, settle or authorize.
 */
class ResourceDecisionTraceRecorder(
    private val ledger: DecisionTraceLedger,
) {
    suspend fun recordReservation(
        binding: ResourceExecutionBinding,
        reservation: ResourceBudgetReservation,
        reasonCodes: List<String>,
        recordedAt: Instant = reservation.createdAt,
    ) = ledger.append(
        binding.traceId,
        nodes = listOf(
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.RESOURCE_CONSTRAINT,
                sourceType = "resource-reservation",
                sourceId = reservation.id.value,
                sourceRevision = binding.revision,
                reasonCodes = (listOf(
                    "DOMAIN_${binding.domain.name}",
                    "ACCOUNT_${binding.accountId.value}",
                    "RESERVED",
                ) + reasonCodes).distinct().sorted(),
                recordedAt = recordedAt,
            ),
        ),
        links = emptyList(),
    )

    suspend fun recordSettlement(
        binding: ResourceExecutionBinding,
        reservation: ResourceBudgetReservation,
        authoritativeOutcomeId: String,
        recordedAt: Instant,
    ) = ledger.append(
        binding.traceId,
        nodes = listOf(
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.RESOURCE_CONSTRAINT,
                sourceType = "resource-reservation",
                sourceId = reservation.id.value,
                sourceRevision = binding.revision,
                reasonCodes = listOf(
                    "DOMAIN_${binding.domain.name}",
                    "STATE_${reservation.state.name}",
                ).sorted(),
                recordedAt = binding.boundAt,
            ),
            DecisionTraceNode.create(
                type = DecisionTraceNodeType.EXECUTION_OUTCOME,
                sourceType = "resource-authoritative-outcome",
                sourceId = authoritativeOutcomeId,
                sourceRevision = binding.revision,
                reasonCodes = listOf("RESOURCE_SETTLEMENT_${reservation.state.name}"),
                recordedAt = recordedAt,
            ),
        ),
        links = listOf(
            DecisionTraceLink(
                DecisionTraceNode.create(
                    type = DecisionTraceNodeType.RESOURCE_CONSTRAINT,
                    sourceType = "resource-reservation",
                    sourceId = reservation.id.value,
                    sourceRevision = binding.revision,
                    reasonCodes = listOf(
                        "DOMAIN_${binding.domain.name}",
                        "STATE_${reservation.state.name}",
                    ).sorted(),
                    recordedAt = binding.boundAt,
                ).id,
                DecisionTraceNode.create(
                    type = DecisionTraceNodeType.EXECUTION_OUTCOME,
                    sourceType = "resource-authoritative-outcome",
                    sourceId = authoritativeOutcomeId,
                    sourceRevision = binding.revision,
                    reasonCodes = listOf("RESOURCE_SETTLEMENT_${reservation.state.name}"),
                    recordedAt = recordedAt,
                ).id,
                DecisionTraceLinkType.CONSTRAINS,
            ),
        ),
    )
}
