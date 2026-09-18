package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.extension.ExtensionCandidate
import app.lifeos.core.runtime.extension.ExtensionKind
import app.lifeos.core.runtime.extension.ExtensionValidationBundle
import app.lifeos.core.runtime.extension.ExtensionWorkshopArtifact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ExtensionEvolutionAdmissionGateTest {
    @Test
    fun admitsWorldEquationCandidateOnlyAsGenericEvolutionSubjects() {
        val fixture = EvolutionFixtureFactory.worldEquation()

        val admission = ExtensionEvolutionAdmissionGate().admit(
            candidate = fixture.candidate,
            workshopArtifact = fixture.workshopArtifact,
            validation = fixture.validation,
            requestedSubjects = setOf(
                ControlledEvolutionSubjectKind.WORLD_COEFFICIENT_SET,
                ControlledEvolutionSubjectKind.WORLD_EQUATION_VERSION,
            ),
            baselineFingerprints = mapOf(
                ControlledEvolutionSubjectKind.WORLD_COEFFICIENT_SET to "coeff-baseline-v1",
                ControlledEvolutionSubjectKind.WORLD_EQUATION_VERSION to "equation-baseline-v1",
            ),
        )

        assertEquals(
            setOf(
                ControlledEvolutionSubjectKind.WORLD_COEFFICIENT_SET,
                ControlledEvolutionSubjectKind.WORLD_EQUATION_VERSION,
            ),
            admission.subjects.mapTo(linkedSetOf()) { it.kind },
        )
        assertFalse(admission.activationAllowed)
        admission.subjects.forEach { assertFalse(it.activationAllowed) }
    }

    @Test
    fun blocksStrategyAdmissionFromExtensionCandidate() {
        val fixture = EvolutionFixtureFactory.signal()

        assertFailsWith<IllegalArgumentException> {
            ExtensionEvolutionAdmissionGate().admit(
                candidate = fixture.candidate,
                workshopArtifact = fixture.workshopArtifact,
                validation = fixture.validation,
                requestedSubjects = setOf(ControlledEvolutionSubjectKind.STRATEGY),
            )
        }
    }

    @Test
    fun blocksTopologySubjectForSignalOnlyCandidate() {
        val fixture = EvolutionFixtureFactory.signal()

        assertFailsWith<IllegalArgumentException> {
            ExtensionEvolutionAdmissionGate().admit(
                candidate = fixture.candidate,
                workshopArtifact = fixture.workshopArtifact,
                validation = fixture.validation,
                requestedSubjects = setOf(ControlledEvolutionSubjectKind.WORLD_TOPOLOGY),
            )
        }
    }

    private object EvolutionFixtureFactory {
        fun signal(): Fixture = fixture(setOf(ExtensionKind.WORLD_SIGNAL_PACK))
        fun worldEquation(): Fixture = fixture(setOf(ExtensionKind.WORLD_EQUATION_PACK))

        private fun fixture(kinds: Set<ExtensionKind>): Fixture {
            val candidate = minimalCandidate(kinds)
            val spec = app.lifeos.core.runtime.extension.ExtensionWorkshopPlanner().plan(
                candidate = candidate,
                sourceCommit = "0123456789abcdef0123456789abcdef01234567",
                allowedPathPrefixes = setOf("core/runtime"),
                requiredTestPaths = setOf("core/runtime/src/test"),
            )
            val buildEvidence = app.lifeos.core.runtime.extension.ExtensionWorkshopBuildEvidence(
                id = "build-evidence-${kinds.first().name.lowercase()}",
                sourceCommit = spec.sourceCommit,
                branchName = "extension-candidate/test",
                branchHeadCommit = "89abcdef0123456789abcdef0123456789abcdef",
                debugArtifactRef = "app/build/outputs/apk/debug/app-debug.apk",
                debugArtifactSha256 = "a".repeat(64),
                activationAllowed = false,
            )
            val workshopArtifact = app.lifeos.core.runtime.extension.ExtensionWorkshopArtifact.create(
                spec,
                candidate,
                buildEvidence,
            )
            val validation = ExtensionValidationBundle.create(
                workshopArtifact = workshopArtifact,
                sandbox = app.lifeos.core.runtime.extension.ExtensionValidationEvidence(
                    workshopArtifactId = workshopArtifact.id,
                    suite = app.lifeos.core.runtime.extension.ExtensionValidationSuite.SANDBOX,
                    validatorId = "sandbox-validator",
                    producerId = "buildstudio-host",
                    passed = true,
                    evidenceFingerprint = "sandbox-pass",
                ),
                gold = app.lifeos.core.runtime.extension.ExtensionValidationEvidence(
                    workshopArtifactId = workshopArtifact.id,
                    suite = app.lifeos.core.runtime.extension.ExtensionValidationSuite.GOLD,
                    validatorId = "gold-validator",
                    producerId = "buildstudio-host",
                    passed = true,
                    evidenceFingerprint = "gold-pass",
                ),
            )
            return Fixture(candidate, workshopArtifact, validation)
        }

        private fun minimalCandidate(kinds: Set<ExtensionKind>): ExtensionCandidate {
            val gap = app.lifeos.core.runtime.extension.ExtensionGap.create(
                kind = app.lifeos.core.runtime.extension.ExtensionGapKind.EQUATION_COVERAGE_GAP,
                semanticKey = "gap",
                reason = "coverage-missing",
                sourceFingerprint = "source-v1",
                requiredExtensionKinds = kinds,
            )
            val requestId = app.lifeos.core.runtime.deepsearch.DeepSearchRequestId("request-1")
            val evidenceId = app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceId("evidence-1")
            val claimId = app.lifeos.core.runtime.deepsearch.DeepSearchHypothesisId("claim-1")
            val graph = app.lifeos.core.runtime.extension.DeepSearchClaimGraph.create(
                requestId = requestId,
                sourceStatus = app.lifeos.core.runtime.deepsearch.DeepSearchStatus.UNRESOLVED,
                evidenceNodes = listOf(
                    app.lifeos.core.runtime.extension.DeepSearchEvidenceNode(
                        app.lifeos.core.runtime.deepsearch.DeepSearchEvidence(
                            id = evidenceId,
                            requestId = requestId,
                            branchId = app.lifeos.core.runtime.deepsearch.DeepSearchBranchId("branch-1"),
                            sourceId = "source-1",
                            statement = "evidence",
                            confidence = 0.8,
                            sourcePhotonId = null,
                            fieldEvidenceId = null,
                            contradiction = false,
                        )
                    )
                ),
                claimNodes = listOf(
                    app.lifeos.core.runtime.extension.DeepSearchClaimNode(
                        hypothesis = app.lifeos.core.runtime.deepsearch.DeepSearchHypothesis(
                            id = claimId,
                            requestId = requestId,
                            statement = "claim",
                            semanticTerms = setOf("claim"),
                            confidence = 0.8,
                            evidenceIds = setOf(evidenceId),
                        ),
                        branchIds = setOf("branch-1"),
                    )
                ),
                edges = listOf(
                    app.lifeos.core.runtime.extension.DeepSearchClaimEdge(
                        evidenceId = evidenceId,
                        hypothesisId = claimId,
                        kind = app.lifeos.core.runtime.extension.DeepSearchClaimEdgeKind.SUPPORTS,
                    )
                ),
                sourceTraceFingerprint = "trace-v1",
            )
            return app.lifeos.core.runtime.extension.ExtensionCandidateFactory().propose(
                app.lifeos.core.runtime.extension.ExtensionCandidateRequest(
                    gap = gap,
                    claimGraph = graph,
                    selectedClaimIds = setOf(claimId),
                    selectedEvidenceIds = setOf(evidenceId),
                    rationale = "evidence-bound candidate",
                )
            )
        }
    }

    private data class Fixture(
        val candidate: ExtensionCandidate,
        val workshopArtifact: ExtensionWorkshopArtifact,
        val validation: ExtensionValidationBundle,
    )
}
