package app.lifeos.core.runtime.capability

import java.time.Instant

/** One bounded, expectation-bearing invocation used to accumulate real trial evidence. */
data class GeneratedToolTrialInvocation(
    val toolId: String,
    val invocationId: String,
    val input: String,
    val expectedOutput: String,
    val requestedPermissions: Set<ToolPermission> = emptySet(),
) {
    init {
        require(toolId.isNotBlank()) { "Trial tool id must not be blank" }
        require(invocationId.isNotBlank()) { "Trial invocation id must not be blank" }
    }
}

sealed interface GeneratedToolTrialExecutionResult {
    data class Completed(
        val output: String,
        val trial: GeneratedToolTrialRecordResult,
    ) : GeneratedToolTrialExecutionResult

    data class Failed(
        val reason: String,
        val trial: GeneratedToolTrialRecordResult,
    ) : GeneratedToolTrialExecutionResult

    data class Blocked(
        val reasons: List<String>,
        val trial: GeneratedToolTrialRecordResult? = null,
    ) : GeneratedToolTrialExecutionResult {
        init {
            require(reasons.isNotEmpty()) { "Blocked trial execution requires a reason" }
        }
    }
}

/**
 * The only executable bridge for bounded generated tools while they are in TRIAL.
 *
 * A sandbox permit is necessary but not sufficient. Every invocation is additionally bound to the
 * exact immutable executable artifact that produced the lifecycle record's source/build hashes and
 * capability contract. Missing, corrupt or stale executable material is recorded as a safety
 * violation and quarantined through the existing lifecycle before any program instruction runs.
 */
class GeneratedToolTrialRunner(
    private val tools: GeneratedToolRegistry,
    private val lifecycle: GeneratedToolLifecycleCoordinator,
    private val artifacts: GeneratedToolArtifactRepository,
    private val interpreter: GeneratedToolProgramInterpreter = GeneratedToolProgramInterpreter(),
    private val now: () -> Instant = Instant::now,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun execute(invocation: GeneratedToolTrialInvocation): GeneratedToolTrialExecutionResult {
        val record = requireNotNull(tools.get(invocation.toolId)) {
            "Unknown generated tool ${invocation.toolId}"
        }
        require(record.state == GeneratedToolState.TRIAL) {
            "Generated-tool trial runner executes only TRIAL tools"
        }

        val permit = when (
            val decision = lifecycle.authorizeTrialInvocation(
                toolId = invocation.toolId,
                invocationId = invocation.invocationId,
                requestedPermissions = invocation.requestedPermissions,
            )
        ) {
            is GeneratedToolInvocationDecision.Denied -> {
                return GeneratedToolTrialExecutionResult.Blocked(decision.reasons)
            }
            is GeneratedToolInvocationDecision.Granted -> decision.permit
        }
        require(permit.toolId == invocation.toolId && permit.invocationId == invocation.invocationId) {
            "Sandbox permit identity does not match trial invocation"
        }
        require(permit.grantedPermissions == invocation.requestedPermissions) {
            "Sandbox permit permissions do not match trial invocation"
        }

        val artifact = try {
            artifacts.load(invocation.toolId)
        } catch (_: Exception) {
            return quarantineArtifactFailure(invocation, listOf("artifact-load-failed"))
        } ?: return quarantineArtifactFailure(invocation, listOf("artifact-missing"))

        val program = runCatching { GeneratedToolProgramCodec.decode(artifact.canonicalProgram) }
            .getOrElse {
                return quarantineArtifactFailure(invocation, listOf("artifact-program-invalid"))
            }
        val bindingReasons = artifactBindingReasons(record, artifact, program)
        if (bindingReasons.isNotEmpty()) {
            return quarantineArtifactFailure(invocation, bindingReasons)
        }

        val startedAt = nanoTime()
        val execution = runCatching { interpreter.execute(program, invocation.input) }
        val latencyMs = elapsedMillis(startedAt, nanoTime())
        val output = execution.getOrNull()
        val result = GeneratedToolTrialResult(
            invocationId = invocation.invocationId,
            success = execution.isSuccess,
            producedExpectedOutput = output == invocation.expectedOutput,
            safetyViolation = false,
            latencyMs = latencyMs,
            recordedAt = now(),
        )
        val recorded = lifecycle.recordTrial(invocation.toolId, result)

        return if (output != null) {
            GeneratedToolTrialExecutionResult.Completed(output, recorded)
        } else {
            GeneratedToolTrialExecutionResult.Failed("bounded-program-execution-failed", recorded)
        }
    }

    private suspend fun quarantineArtifactFailure(
        invocation: GeneratedToolTrialInvocation,
        reasons: List<String>,
    ): GeneratedToolTrialExecutionResult.Blocked {
        val current = requireNotNull(tools.get(invocation.toolId)) {
            "Unknown generated tool ${invocation.toolId}"
        }
        if (current.state != GeneratedToolState.TRIAL) {
            return GeneratedToolTrialExecutionResult.Blocked(reasons.distinct().sorted())
        }
        val result = GeneratedToolTrialResult(
            invocationId = invocation.invocationId,
            success = false,
            producedExpectedOutput = false,
            safetyViolation = true,
            latencyMs = 0,
            recordedAt = now(),
        )
        return GeneratedToolTrialExecutionResult.Blocked(
            reasons = reasons.distinct().sorted(),
            trial = lifecycle.recordTrial(invocation.toolId, result),
        )
    }

    private fun artifactBindingReasons(
        record: GeneratedToolRecord,
        artifact: GeneratedToolArtifact,
        program: GeneratedToolProgram,
    ): List<String> = buildList {
        if (!artifact.matches(record)) add("artifact-lifecycle-hash-mismatch")
        if (!GeneratedToolArtifact.isBoundedSourceHash(record.manifest.sourceHash)) {
            add("lifecycle-source-hash-not-bounded")
        }
        if (program.toolId != record.manifest.toolId) add("program-tool-id-mismatch")
        if (program.capabilityId != record.manifest.sourceCapability) add("program-capability-mismatch")
        if (program.requiredInputs != record.manifest.requiredInputs) add("program-input-contract-mismatch")
        if (program.requiredOutputs != record.manifest.requiredOutputs) add("program-output-contract-mismatch")
        if (!program.executable) add("program-not-executable")
    }.distinct().sorted()

    private fun elapsedMillis(startedAt: Long, finishedAt: Long): Long {
        if (finishedAt <= startedAt) return 0
        return (finishedAt - startedAt) / NANOS_PER_MILLISECOND
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
