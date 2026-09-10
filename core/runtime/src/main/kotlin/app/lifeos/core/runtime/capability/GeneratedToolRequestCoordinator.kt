package app.lifeos.core.runtime.capability

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface GeneratedToolRequestExecutionResult {
    data class Completed(
        val requestId: String,
        val genesis: GeneratedToolGenesisResult,
        val duplicate: Boolean,
    ) : GeneratedToolRequestExecutionResult

    data class Blocked(
        val requestId: String,
        val reason: String,
    ) : GeneratedToolRequestExecutionResult
}

/**
 * Trusted boundary between a persisted capability-gap request and Genesis.
 * A request Photon is intent/audit evidence only. Genesis requires a distinct approval that binds
 * the exact request fingerprint and exact gap. Nothing in this coordinator can activate a tool.
 */
class GeneratedToolRequestCoordinator(
    private val generate: suspend (CapabilityGap) -> GeneratedToolGenesisResult,
) {
    constructor(genesis: GeneratedToolGenesisCoordinator) : this(genesis::generateFor)

    private val mutex = Mutex()
    private val completed = mutableMapOf<String, GeneratedToolGenesisResult>()

    suspend fun generateApproved(
        request: GeneratedToolRequest,
        approval: ToolGenerationApproval,
        gap: CapabilityGap,
    ): GeneratedToolRequestExecutionResult = mutex.withLock {
        if (!request.matches(gap)) {
            return@withLock GeneratedToolRequestExecutionResult.Blocked(
                requestId = request.id,
                reason = "request-gap-mismatch",
            )
        }
        if (!approval.matches(request)) {
            return@withLock GeneratedToolRequestExecutionResult.Blocked(
                requestId = request.id,
                reason = "approval-request-mismatch",
            )
        }
        require(!request.activationAllowed && !approval.activationAllowed) {
            "Generated-tool request/approval evidence must remain non-activating"
        }

        completed[request.id]?.let { existing ->
            return@withLock GeneratedToolRequestExecutionResult.Completed(
                requestId = request.id,
                genesis = existing,
                duplicate = true,
            )
        }

        val result = generate(gap)
        completed[request.id] = result
        GeneratedToolRequestExecutionResult.Completed(
            requestId = request.id,
            genesis = result,
            duplicate = false,
        )
    }
}
