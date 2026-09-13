package app.lifeos.next.kernel

import app.lifeos.core.runtime.artifact.ArtifactKind
import app.lifeos.core.runtime.artifact.OwnerAssetReviewCandidate
import app.lifeos.core.runtime.artifact.OwnerAssetReviewCandidateId
import app.lifeos.core.runtime.artifact.OwnerAssetReviewSubjectType
import app.lifeos.core.runtime.capability.GeneratedToolArtifactRepository
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolOwnerReviewGate
import app.lifeos.core.runtime.capability.GeneratedToolOwnerReviewGateRegistry
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GeneratedToolTrialAdmissionResult

/**
 * Productive private-owner boundary for the exact generated source that already passed build, test,
 * security and capability verification. Generation approval alone never enters TRIAL: the immutable
 * encrypted artifact is staged here and only the exact approved revision may cross VERIFIED -> TRIAL.
 */
internal object GeneratedToolOwnerReviewRuntime : GeneratedToolOwnerReviewGate {
    @Volatile
    private var artifacts: GeneratedToolArtifactRepository? = null

    @Volatile
    private var tools: GeneratedToolRegistry? = null

    @Volatile
    private var lifecycle: GeneratedToolLifecycleCoordinator? = null

    fun configure(
        artifactRepository: GeneratedToolArtifactRepository,
        toolRegistry: GeneratedToolRegistry,
        lifecycleCoordinator: GeneratedToolLifecycleCoordinator,
    ) {
        artifacts = artifactRepository
        tools = toolRegistry
        lifecycle = lifecycleCoordinator
        GeneratedToolOwnerReviewGateRegistry.install(this)
    }

    override suspend fun stage(record: GeneratedToolRecord): OwnerAssetReviewCandidateId {
        require(record.state == GeneratedToolState.VERIFIED) {
            "Only VERIFIED generated tools may enter owner code review"
        }
        val artifactRepository = requireNotNull(artifacts) {
            "Generated-tool artifact repository is unavailable"
        }
        val artifact = requireNotNull(artifactRepository.load(record.manifest.toolId)) {
            "Verified generated tool lost its encrypted artifact"
        }
        require(artifact.matches(record)) {
            "Generated-tool artifact does not match the VERIFIED lifecycle record"
        }
        val review = requireNotNull(OwnerAssetReviewRuntimeRegistry.currentOrNull()) {
            "Owner asset review runtime is unavailable"
        }
        val candidate = OwnerAssetReviewCandidate.create(
            subjectType = OwnerAssetReviewSubjectType.GENERATED_TOOL,
            subjectId = artifact.toolId,
            revisionKey = artifact.id,
            kind = ArtifactKind.CODE,
            title = "Generated tool ${artifact.toolId}",
            targetMimeType = GENERATED_TOOL_MIME,
            createdAt = artifact.createdAt,
            participatingModules = REVIEW_MODULES,
            inputPhotonIds = emptySet(),
            stagedPhotons = emptyList(),
            previewText = artifact.canonicalProgram,
            metadata = reviewMetadata(record, artifact.id, artifact.sourceHash, artifact.buildHash),
        )
        review.stage(candidate)
        return candidate.id
    }

    /**
     * Idempotent post-approval effect invoked by OwnerAssetReviewCoordinator both live and during
     * recovery. It never promotes ACTIVE; it only admits the exact reviewed VERIFIED revision to the
     * existing sandbox TRIAL gate. Safety-settled later states remain untouched on replay.
     */
    suspend fun applyApproved(candidate: OwnerAssetReviewCandidate) {
        if (candidate.subjectType != OwnerAssetReviewSubjectType.GENERATED_TOOL) return
        require(candidate.kind == ArtifactKind.CODE) {
            "Generated-tool owner review candidate must be CODE"
        }
        require(candidate.targetMimeType == GENERATED_TOOL_MIME) {
            "Generated-tool owner review MIME changed after staging"
        }
        require(candidate.participatingModules == REVIEW_MODULES) {
            "Generated-tool owner review module set changed after staging"
        }
        require(candidate.inputPhotonIds.isEmpty() && candidate.stagedPhotons.isEmpty()) {
            "Generated-tool review must not smuggle Photon publication authority"
        }
        require(candidate.materializedAsset == null) {
            "Generated-tool review must bind the encrypted tool artifact, not a binary asset"
        }

        val artifactRepository = requireNotNull(artifacts) {
            "Generated-tool artifact repository is unavailable"
        }
        val toolRegistry = requireNotNull(tools) {
            "Generated-tool registry is unavailable"
        }
        val lifecycleCoordinator = requireNotNull(lifecycle) {
            "Generated-tool lifecycle is unavailable"
        }
        val artifact = requireNotNull(artifactRepository.load(candidate.subjectId)) {
            "Approved generated-tool artifact is missing"
        }
        require(candidate.subjectId == artifact.toolId) {
            "Owner review targets another generated-tool id"
        }
        require(candidate.revisionKey == artifact.id) {
            "Owner review targets a stale generated-tool revision"
        }
        require(candidate.previewText == artifact.canonicalProgram) {
            "Owner review source does not match encrypted generated-tool artifact"
        }

        val record = requireNotNull(toolRegistry.get(artifact.toolId)) {
            "Approved generated tool is absent from lifecycle registry"
        }
        require(artifact.matches(record)) {
            "Approved generated-tool artifact no longer matches lifecycle state"
        }
        require(
            candidate.metadata == reviewMetadata(
                record = record,
                artifactId = artifact.id,
                sourceHash = artifact.sourceHash,
                buildHash = artifact.buildHash,
            )
        ) {
            "Generated-tool owner review metadata no longer matches VERIFIED evidence"
        }

        when (record.state) {
            GeneratedToolState.VERIFIED -> when (
                lifecycleCoordinator.admitToTrial(artifact.toolId)
            ) {
                is GeneratedToolTrialAdmissionResult.TrialStarted -> Unit
                is GeneratedToolTrialAdmissionResult.Rejected -> Unit
            }

            GeneratedToolState.TRIAL,
            GeneratedToolState.ACTIVE,
            GeneratedToolState.QUARANTINED,
            GeneratedToolState.REJECTED,
            GeneratedToolState.RETIRED -> Unit

            GeneratedToolState.GENERATED,
            GeneratedToolState.BUILT,
            GeneratedToolState.TESTED -> error(
                "Approved generated tool regressed before VERIFIED: ${record.state}"
            )
        }
    }

    private fun reviewMetadata(
        record: GeneratedToolRecord,
        artifactId: String,
        sourceHash: String,
        buildHash: String,
    ): Map<String, String> = mapOf(
        "artifactId" to artifactId,
        "sourceHash" to sourceHash,
        "buildHash" to buildHash,
        "capabilityId" to record.manifest.sourceCapability.value,
        "verificationConfidence" to record.verificationConfidence.toString(),
        "permissions" to record.manifest.permissions
            .sortedBy { it.name }
            .joinToString(",") { it.name },
    )

    private const val GENERATED_TOOL_MIME = "application/vnd.lifeos.generated-tool+text"
    private val REVIEW_MODULES = setOf(
        "tool-workshop",
        "generated-tool-security",
        "generated-tool-verifier",
    )
}
