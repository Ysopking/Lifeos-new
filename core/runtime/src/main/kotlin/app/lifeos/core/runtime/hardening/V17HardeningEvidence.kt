package app.lifeos.core.runtime.hardening

import java.security.MessageDigest

enum class V17CrashBoundary {
    GOAL_PERSISTENCE,
    CONVERGENCE_PERSISTENCE,
    OWNER_POLICY_PERSISTENCE,
    RESOURCE_RESERVATION,
    RESOURCE_OUTCOME,
    RESOURCE_SETTLEMENT,
    SELF_HEALING_PERSISTENCE,
    EVOLUTION_PERSISTENCE,
    HOT_SWAP_PREPARE,
    HOT_SWAP_EXPOSE,
    TOOL_WORKSHOP_PERSISTENCE,
    DEEP_SEARCH_CHECKPOINT,
    ARTIFACT_FINALIZATION,
}

enum class V17CrashWindow {
    BEFORE_BOUNDARY,
    AFTER_BOUNDARY,
}

enum class V17CorruptionCase {
    VALID_CURRENT_CODEC,
    TRUNCATION,
    UNSUPPORTED_VERSION,
    AUTHENTICATED_CIPHERTEXT_CORRUPTION,
    OVERSIZED_PAYLOAD,
    PARTIAL_READABLE,
    PARTIAL_UNREADABLE,
    PREVIOUS_SCHEMA_MIGRATION,
}

enum class V17OwnerRevocationWindow {
    BEFORE_PREPARE,
    AFTER_PREPARE_BEFORE_EXPOSE,
    ACROSS_RESTART,
    UNREADABLE_POLICY_STORE,
    POLICY_HISTORY_NO_DEFAULT_RECREATION,
}

enum class V17EnduranceScenario {
    ORPHAN_RESERVATION,
    DURABLE_OUTCOME_BEFORE_SETTLEMENT,
    THERMAL_PRESSURE,
    BATTERY_PRESSURE,
    STORAGE_PRESSURE,
    MEMORY_PRESSURE,
    FOREGROUND_BACKGROUND,
    REPEATED_RESTART,
    SOAK,
}

enum class V17Journey {
    GOAL_TO_EXPLANATION,
    GAP_TOOLWORKSHOP_TO_COMPLETION,
    PROVIDER_REGRESSION_TO_RECOVERY,
    DEEPSEARCH_ARTIFACT_TO_COGNITION,
}

enum class V17EvidenceFailureCode {
    MISSING_INTEGRITY,
    EVIDENCE_ID_MISMATCH,
    IDENTITY_HASH_MISMATCH,
    CONTENT_HASH_MISMATCH,
    PROVENANCE_HASH_MISMATCH,
    LINEAGE_HASH_MISMATCH,
    RECORD_HASH_MISMATCH,
    CANDIDATE_SHA_MISMATCH,
    PERSISTENCE_ID_MISMATCH,
    PERSISTED_PAYLOAD_HASH_MISMATCH,
    DUPLICATE_PRODUCTIVE_EFFECTS,
    MISSING_TRACE_EVIDENCE,
    MISSING_RESOURCE_EVIDENCE,
    MISSING_OUTCOME_EVIDENCE,
}

data class V17EvidenceIntegrity(
    val schemaVersion: Int,
    val evidenceId: String,
    val identitySha256: String,
    val contentSha256: String,
    val provenanceSha256: String,
    val lineageSha256: String,
    val recordSha256: String,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported V17 evidence-integrity schema" }
        require(evidenceId.matches(Regex("v17-evidence-[0-9a-f]{64}"))) { "Invalid V17 evidence identity" }
        require(identitySha256.isSha256())
        require(contentSha256.isSha256())
        require(provenanceSha256.isSha256())
        require(lineageSha256.isSha256())
        require(recordSha256.isSha256())
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

data class V17AcceptanceEvidence(
    val candidateSha: String,
    val scenarioId: String,
    val environment: String,
    val runId: String,
    val observedDurableState: String,
    val duplicateEffectCount: Long,
    val traceIds: List<String> = emptyList(),
    val policyIds: List<String> = emptyList(),
    val resourceIds: List<String> = emptyList(),
    val outcomeIds: List<String> = emptyList(),
    val apkSha256: String? = null,
    val parentEvidenceSha256s: List<String> = emptyList(),
    val integrity: V17EvidenceIntegrity? = null,
) {
    init {
        require(candidateSha.matches(Regex("[0-9a-f]{40,64}"))) { "Candidate SHA must be hex" }
        require(scenarioId.isNotBlank())
        require(environment.isNotBlank())
        require(runId.isNotBlank())
        require(observedDurableState.isNotBlank())
        require(duplicateEffectCount >= 0L)
        require(traceIds.none { it.isBlank() })
        require(policyIds.none { it.isBlank() })
        require(resourceIds.none { it.isBlank() })
        require(outcomeIds.none { it.isBlank() })
        require(apkSha256 == null || apkSha256.isSha256())
        require(parentEvidenceSha256s.all { it.isSha256() })
    }

    val passesExactlyOnce: Boolean
        get() = duplicateEffectCount == 0L

    /**
     * Produces a deterministic, content-addressed proof record. Replaying the same
     * evidence yields the same identity and hashes regardless of reference ordering.
     */
    fun seal(): V17AcceptanceEvidence = copy(integrity = V17EvidenceIntegrityEngine.seal(this))
}

data class V17PersistedAcceptanceEvidence(
    val persistenceId: String,
    val payloadSha256: String,
    val evidence: V17AcceptanceEvidence,
) {
    init {
        require(persistenceId.isNotBlank())
        require(payloadSha256.isSha256())
    }

    companion object {
        fun from(evidence: V17AcceptanceEvidence): V17PersistedAcceptanceEvidence {
            V17AcceptanceGate.requirePass(evidence)
            val integrity = requireNotNull(evidence.integrity)
            return V17PersistedAcceptanceEvidence(
                persistenceId = integrity.evidenceId,
                payloadSha256 = V17EvidenceIntegrityEngine.persistedPayloadSha256(evidence),
                evidence = evidence,
            )
        }
    }
}

data class V17EvidenceVerification(
    val failures: List<V17EvidenceFailureCode>,
) {
    val passed: Boolean
        get() = failures.isEmpty()
}

/**
 * V17 intentionally does not turn evidence collection into production authority.
 * It only rejects incomplete, stale, corrupted, or lineage-inconsistent proof records.
 */
object V17AcceptanceGate {
    fun verify(
        evidence: V17AcceptanceEvidence,
        expectedCandidateSha: String? = null,
    ): V17EvidenceVerification {
        if (expectedCandidateSha != null) {
            require(expectedCandidateSha.matches(Regex("[0-9a-f]{40,64}"))) {
                "Expected candidate SHA must be hex"
            }
        }

        val failures = linkedSetOf<V17EvidenceFailureCode>()
        val integrity = evidence.integrity
        if (integrity == null) {
            failures += V17EvidenceFailureCode.MISSING_INTEGRITY
        } else {
            val expected = V17EvidenceIntegrityEngine.seal(evidence)
            if (integrity.evidenceId != expected.evidenceId) {
                failures += V17EvidenceFailureCode.EVIDENCE_ID_MISMATCH
            }
            if (integrity.identitySha256 != expected.identitySha256) {
                failures += V17EvidenceFailureCode.IDENTITY_HASH_MISMATCH
            }
            if (integrity.contentSha256 != expected.contentSha256) {
                failures += V17EvidenceFailureCode.CONTENT_HASH_MISMATCH
            }
            if (integrity.provenanceSha256 != expected.provenanceSha256) {
                failures += V17EvidenceFailureCode.PROVENANCE_HASH_MISMATCH
            }
            if (integrity.lineageSha256 != expected.lineageSha256) {
                failures += V17EvidenceFailureCode.LINEAGE_HASH_MISMATCH
            }
            if (integrity.recordSha256 != expected.recordSha256) {
                failures += V17EvidenceFailureCode.RECORD_HASH_MISMATCH
            }
        }
        if (expectedCandidateSha != null && evidence.candidateSha != expectedCandidateSha) {
            failures += V17EvidenceFailureCode.CANDIDATE_SHA_MISMATCH
        }
        if (!evidence.passesExactlyOnce) {
            failures += V17EvidenceFailureCode.DUPLICATE_PRODUCTIVE_EFFECTS
        }
        return V17EvidenceVerification(failures.toList())
    }

    fun verifyPersisted(
        persisted: V17PersistedAcceptanceEvidence,
        expectedCandidateSha: String? = null,
    ): V17EvidenceVerification {
        val failures = verify(persisted.evidence, expectedCandidateSha).failures.toMutableList()
        val integrity = persisted.evidence.integrity
        if (integrity == null || persisted.persistenceId != integrity.evidenceId) {
            failures += V17EvidenceFailureCode.PERSISTENCE_ID_MISMATCH
        }
        if (persisted.payloadSha256 != V17EvidenceIntegrityEngine.persistedPayloadSha256(persisted.evidence)) {
            failures += V17EvidenceFailureCode.PERSISTED_PAYLOAD_HASH_MISMATCH
        }
        return V17EvidenceVerification(failures.distinct())
    }

    fun verifyCompleteJourneyEvidence(
        journey: V17Journey,
        evidence: V17AcceptanceEvidence,
        expectedCandidateSha: String? = null,
    ): V17EvidenceVerification {
        val failures = verify(evidence, expectedCandidateSha).failures.toMutableList()
        if (evidence.traceIds.isEmpty()) {
            failures += V17EvidenceFailureCode.MISSING_TRACE_EVIDENCE
        }
        if (evidence.resourceIds.isEmpty()) {
            failures += V17EvidenceFailureCode.MISSING_RESOURCE_EVIDENCE
        }
        if (evidence.outcomeIds.isEmpty()) {
            failures += V17EvidenceFailureCode.MISSING_OUTCOME_EVIDENCE
        }
        // Keep the journey parameter in the API because callers use it as the typed
        // acceptance-contract selector, even though the current mandatory evidence
        // dimensions are shared by all V17 end-to-end journeys.
        @Suppress("UNUSED_VARIABLE")
        val typedJourney = journey
        return V17EvidenceVerification(failures.distinct())
    }

    fun requirePass(
        evidence: V17AcceptanceEvidence,
        expectedCandidateSha: String? = null,
    ): V17AcceptanceEvidence {
        val verification = verify(evidence, expectedCandidateSha)
        require(verification.passed) {
            "V17 evidence verification failed: ${verification.failures.joinToString(",") { it.name }}"
        }
        return evidence
    }

    fun requirePersistedPass(
        persisted: V17PersistedAcceptanceEvidence,
        expectedCandidateSha: String? = null,
    ): V17PersistedAcceptanceEvidence {
        val verification = verifyPersisted(persisted, expectedCandidateSha)
        require(verification.passed) {
            "V17 persisted evidence verification failed: ${verification.failures.joinToString(",") { it.name }}"
        }
        return persisted
    }

    fun requireCompleteJourneyEvidence(
        journey: V17Journey,
        evidence: V17AcceptanceEvidence,
        expectedCandidateSha: String? = null,
    ): V17AcceptanceEvidence {
        val verification = verifyCompleteJourneyEvidence(journey, evidence, expectedCandidateSha)
        require(verification.passed) {
            "$journey evidence verification failed: ${verification.failures.joinToString(",") { it.name }}"
        }
        return evidence
    }
}

private object V17EvidenceIntegrityEngine {
    private const val SCHEMA = "v17-evidence-integrity/v1"

    fun seal(evidence: V17AcceptanceEvidence): V17EvidenceIntegrity {
        val identitySha256 = sha256(identityCanonical(evidence))
        val contentSha256 = sha256(contentCanonical(evidence))
        val provenanceSha256 = sha256(provenanceCanonical(evidence))
        val lineageSha256 = sha256(lineageCanonical(evidence))
        val recordSha256 = sha256(
            listOf(
                scalar("schema", SCHEMA),
                scalar("identitySha256", identitySha256),
                scalar("contentSha256", contentSha256),
                scalar("provenanceSha256", provenanceSha256),
                scalar("lineageSha256", lineageSha256),
            ).joinToString("\n")
        )
        return V17EvidenceIntegrity(
            schemaVersion = V17EvidenceIntegrity.CURRENT_SCHEMA_VERSION,
            evidenceId = "v17-evidence-$identitySha256",
            identitySha256 = identitySha256,
            contentSha256 = contentSha256,
            provenanceSha256 = provenanceSha256,
            lineageSha256 = lineageSha256,
            recordSha256 = recordSha256,
        )
    }

    fun persistedPayloadSha256(evidence: V17AcceptanceEvidence): String {
        val integrity = evidence.integrity
        val canonical = listOf(
            scalar("schema", SCHEMA),
            identityCanonical(evidence),
            contentCanonical(evidence),
            provenanceCanonical(evidence),
            lineageCanonical(evidence),
            scalar("integrity.schemaVersion", integrity?.schemaVersion?.toString() ?: "<missing>"),
            scalar("integrity.evidenceId", integrity?.evidenceId ?: "<missing>"),
            scalar("integrity.identitySha256", integrity?.identitySha256 ?: "<missing>"),
            scalar("integrity.contentSha256", integrity?.contentSha256 ?: "<missing>"),
            scalar("integrity.provenanceSha256", integrity?.provenanceSha256 ?: "<missing>"),
            scalar("integrity.lineageSha256", integrity?.lineageSha256 ?: "<missing>"),
            scalar("integrity.recordSha256", integrity?.recordSha256 ?: "<missing>"),
        ).joinToString("\n")
        return sha256(canonical)
    }

    private fun identityCanonical(evidence: V17AcceptanceEvidence): String = listOf(
        scalar("candidateSha", evidence.candidateSha),
        scalar("scenarioId", evidence.scenarioId),
        scalar("environment", evidence.environment),
        scalar("runId", evidence.runId),
    ).joinToString("\n")

    private fun contentCanonical(evidence: V17AcceptanceEvidence): String = listOf(
        identityCanonical(evidence),
        scalar("observedDurableState", evidence.observedDurableState),
        scalar("duplicateEffectCount", evidence.duplicateEffectCount.toString()),
        scalar("apkSha256", evidence.apkSha256 ?: "<none>"),
    ).joinToString("\n")

    private fun provenanceCanonical(evidence: V17AcceptanceEvidence): String = listOf(
        collection("traceIds", evidence.traceIds),
        collection("policyIds", evidence.policyIds),
        collection("resourceIds", evidence.resourceIds),
        collection("outcomeIds", evidence.outcomeIds),
    ).joinToString("\n")

    private fun lineageCanonical(evidence: V17AcceptanceEvidence): String = listOf(
        scalar("candidateSha", evidence.candidateSha),
        scalar("scenarioId", evidence.scenarioId),
        collection("parentEvidenceSha256s", evidence.parentEvidenceSha256s),
    ).joinToString("\n")

    private fun scalar(key: String, value: String): String = "$key=${token(value)}"

    private fun collection(key: String, values: List<String>): String =
        "$key=${values.sorted().joinToString(prefix = "[", postfix = "]", separator = ",") { token(it) }}"

    private fun token(value: String): String = "${value.toByteArray(Charsets.UTF_8).size}:$value"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
