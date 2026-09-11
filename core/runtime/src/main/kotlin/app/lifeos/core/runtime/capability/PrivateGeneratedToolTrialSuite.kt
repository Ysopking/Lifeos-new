package app.lifeos.core.runtime.capability

import kotlinx.coroutines.CancellationException

/**
 * Three expectation-bearing, side-effect-free trial invocations for the bounded private tool
 * archetypes. These probes create real GeneratedToolTrialLedger evidence but never activate or
 * register a generated provider.
 */
class PrivateGeneratedToolTrialSuite(
    private val runner: GeneratedToolTrialRunner,
    private val artifacts: GeneratedToolArtifactRepository,
) {
    suspend fun execute(record: GeneratedToolRecord): PrivateGeneratedToolTrialSuiteResult {
        require(record.state == GeneratedToolState.TRIAL) {
            "Private generated-tool trial suite accepts only TRIAL tools"
        }

        val cases = loadCases(record)
        val selected = cases.take(REQUIRED_CASES)
        if (selected.size < REQUIRED_CASES) {
            val probe = runner.execute(
                GeneratedToolTrialInvocation(
                    toolId = record.manifest.toolId,
                    invocationId = invocationId(record.manifest.toolId, "preflight"),
                    input = "LifeOS trial probe",
                    expectedOutput = "LifeOS trial probe",
                )
            )
            return PrivateGeneratedToolTrialSuiteResult(
                toolId = record.manifest.toolId,
                expectedCases = REQUIRED_CASES,
                executions = listOf(probe),
                suiteFailure = "bounded-trial-cases-unavailable",
            )
        }

        val executions = mutableListOf<GeneratedToolTrialExecutionResult>()
        for (case in selected) {
            val execution = runner.execute(
                GeneratedToolTrialInvocation(
                    toolId = record.manifest.toolId,
                    invocationId = invocationId(record.manifest.toolId, case.id),
                    input = case.input,
                    expectedOutput = case.expected,
                )
            )
            executions += execution
            if (execution is GeneratedToolTrialExecutionResult.Blocked) break
        }

        return PrivateGeneratedToolTrialSuiteResult(
            toolId = record.manifest.toolId,
            expectedCases = REQUIRED_CASES,
            executions = executions,
            suiteFailure = null,
        )
    }

    private suspend fun loadCases(record: GeneratedToolRecord): List<TrialCase> {
        val artifact = try {
            artifacts.load(record.manifest.toolId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()

        val program = runCatching { GeneratedToolProgramCodec.decode(artifact.canonicalProgram) }
            .getOrNull() ?: return emptyList()
        if (program.toolId != record.manifest.toolId || program.instructions.size != 1) return emptyList()
        return casesFor(program.instructions.single().opcode)
    }

    private fun invocationId(toolId: String, caseId: String): String =
        "$toolId/private-trial-v1/$caseId"

    private data class TrialCase(
        val id: String,
        val input: String,
        val expected: String,
    )

    private fun casesFor(opcode: GeneratedToolOpcode): List<TrialCase> = when (opcode) {
        GeneratedToolOpcode.TRIM -> listOf(
            TrialCase("trim-spaces", "  hello  ", "hello"),
            TrialCase("trim-lines", "\nhello\n", "hello"),
            TrialCase("trim-stable", "already-trimmed", "already-trimmed"),
        )
        GeneratedToolOpcode.NORMALIZE_WHITESPACE -> listOf(
            TrialCase("normalize-spaces", "  hello   world  ", "hello world"),
            TrialCase("normalize-lines", "hello\n\tworld", "hello world"),
            TrialCase("normalize-stable", "one two three", "one two three"),
        )
        GeneratedToolOpcode.UPPERCASE -> listOf(
            TrialCase("uppercase-mixed", "LifeOS 123", "LIFEOS 123"),
            TrialCase("uppercase-lower", "abc xyz", "ABC XYZ"),
            TrialCase("uppercase-stable", "ALREADY 42", "ALREADY 42"),
        )
        GeneratedToolOpcode.LOWERCASE -> listOf(
            TrialCase("lowercase-mixed", "LifeOS 123", "lifeos 123"),
            TrialCase("lowercase-upper", "ABC XYZ", "abc xyz"),
            TrialCase("lowercase-stable", "already 42", "already 42"),
        )
        GeneratedToolOpcode.UNSUPPORTED -> emptyList()
    }

    private companion object {
        const val REQUIRED_CASES = 3
    }
}

data class PrivateGeneratedToolTrialSuiteResult(
    val toolId: String,
    val expectedCases: Int,
    val executions: List<GeneratedToolTrialExecutionResult>,
    val suiteFailure: String?,
) {
    init {
        require(toolId.isNotBlank())
        require(expectedCases > 0)
    }

    val finalStats: GeneratedToolTrialStats? = executions.asReversed()
        .firstNotNullOfOrNull { execution ->
            when (execution) {
                is GeneratedToolTrialExecutionResult.Completed -> execution.trial.stats()
                is GeneratedToolTrialExecutionResult.Failed -> execution.trial.stats()
                is GeneratedToolTrialExecutionResult.Blocked -> execution.trial?.stats()
            }
        }

    val completeAndExpected: Boolean =
        suiteFailure == null &&
            executions.size == expectedCases &&
            executions.all { it is GeneratedToolTrialExecutionResult.Completed } &&
            finalStats?.let { stats ->
                stats.trials >= expectedCases &&
                    stats.successes >= expectedCases &&
                    stats.expectedOutputs >= expectedCases &&
                    stats.safetyViolations == 0
            } == true

    private fun GeneratedToolTrialRecordResult.stats(): GeneratedToolTrialStats = when (this) {
        is GeneratedToolTrialRecordResult.Recorded -> stats
        is GeneratedToolTrialRecordResult.Quarantined -> stats
    }
}
