package app.lifeos.core.runtime.capability

import java.nio.charset.StandardCharsets

/**
 * Reconstructs the process-local bounded build catalog from V11 durable stage artifacts.
 * No build or test command is repeated: hashes and prior test evidence are verified before the
 * existing private verifier is allowed to consume the reconstructed catalog entry.
 */
class PrivateToolWorkshopBuildStateRehydrator(
    private val catalog: PrivateToolBuildCatalog,
) : ToolWorkshopBuildStateRehydrator {
    override suspend fun restore(
        source: GeneratedSource,
        build: ToolBuildResult,
        tests: ToolTestResult?,
    ) {
        require(build.success) { "Cannot rehydrate an unsuccessful bounded tool build" }
        require(source.toolId == build.toolId) { "Rehydrated source/build tool id mismatch" }
        val buildHash = requireNotNull(build.buildHash) { "Rehydrated build has no build hash" }
        require(!build.artifactRef.isNullOrBlank()) { "Rehydrated build has no artifact reference" }

        val program = GeneratedToolProgramCodec.decode(source.source)
        require(program.toolId == source.toolId) { "Rehydrated program belongs to another tool" }
        val sourceHash = GeneratedToolArtifact.typedSourceHash(source.source)
        val expectedBuildHash = sha256(
            (GeneratedToolArtifact.BUILD_DOMAIN + source.source).toByteArray(StandardCharsets.UTF_8)
        )
        require(build.sourceHash == sourceHash) { "Rehydrated source hash mismatch" }
        require(buildHash == expectedBuildHash) { "Rehydrated build hash mismatch" }

        catalog.put(
            PrivateBuiltToolProgram(
                program = program,
                source = source.source,
                sourceHash = sourceHash,
                buildHash = buildHash,
            )
        )

        if (tests != null) {
            require(tests.success && tests.failed == 0) {
                "Only successful persisted tests may rehydrate tested build state"
            }
            catalog.markTested(buildHash)
        }
    }
}
