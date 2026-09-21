package app.lifeos.core.runtime.web

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class WebReliabilityVerdict {
    SUPPORTED,
    CONTRADICTED,
    INDETERMINATE,
}

data class WebReliabilityObservation(
    val resource: WebResourceIdentity,
    val claimCandidateId: String,
    val sourceDocumentFingerprint: String,
    val verificationEvidenceFingerprint: String,
    val verified: Boolean,
    val verdict: WebReliabilityVerdict,
    val weight: Double,
    val fingerprint: String,
) {
    init {
        require(claimCandidateId.startsWith(WebClaimCandidate.ID_PREFIX))
        require(sourceDocumentFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(verificationEvidenceFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(weight.isFinite() && weight > 0.0 && weight <= 1.0)
        if (verdict != WebReliabilityVerdict.INDETERMINATE) {
            require(verified) {
                "Supported/contradicted Web reliability observations must be verified"
            }
        }
        if (!verified) {
            require(verdict == WebReliabilityVerdict.INDETERMINATE) {
                "Unverified Web reliability observations must remain indeterminate"
            }
        }
        require(
            fingerprint == observationFingerprint(
                resource = resource,
                claimCandidateId = claimCandidateId,
                sourceDocumentFingerprint = sourceDocumentFingerprint,
                verificationEvidenceFingerprint = verificationEvidenceFingerprint,
                verified = verified,
                verdict = verdict,
                weight = weight,
            )
        )
    }

    val learningEligible: Boolean
        get() = verified && verdict != WebReliabilityVerdict.INDETERMINATE

    companion object {
        fun create(
            resource: WebResourceIdentity,
            claimCandidateId: String,
            sourceDocumentFingerprint: String,
            verificationEvidenceFingerprint: String,
            verified: Boolean,
            verdict: WebReliabilityVerdict,
            weight: Double = 1.0,
        ): WebReliabilityObservation {
            val fingerprint = observationFingerprint(
                resource = resource,
                claimCandidateId = claimCandidateId,
                sourceDocumentFingerprint = sourceDocumentFingerprint,
                verificationEvidenceFingerprint = verificationEvidenceFingerprint,
                verified = verified,
                verdict = verdict,
                weight = weight,
            )
            return WebReliabilityObservation(
                resource = resource,
                claimCandidateId = claimCandidateId,
                sourceDocumentFingerprint = sourceDocumentFingerprint,
                verificationEvidenceFingerprint = verificationEvidenceFingerprint,
                verified = verified,
                verdict = verdict,
                weight = weight,
                fingerprint = fingerprint,
            )
        }
    }
}

data class WebSourceReliabilityPolicy(
    val priorSupportedWeight: Double = 1.0,
    val priorContradictedWeight: Double = 1.0,
    val minimumVerifiedDirectionalObservations: Int = 3,
) {
    init {
        require(priorSupportedWeight.isFinite() && priorSupportedWeight > 0.0)
        require(priorContradictedWeight.isFinite() && priorContradictedWeight > 0.0)
        require(minimumVerifiedDirectionalObservations in 1..100_000)
    }

    fun fingerprint(): String = reliabilityFingerprint(
        "web-source-reliability-policy/v1",
        java.lang.Double.toHexString(priorSupportedWeight),
        java.lang.Double.toHexString(priorContradictedWeight),
        minimumVerifiedDirectionalObservations.toString(),
    )
}

data class WebSourceReliabilityProfile(
    val origin: WebOriginIdentity,
    val observationFingerprints: List<String>,
    val verifiedDirectionalObservations: Int,
    val supportedWeight: Double,
    val contradictedWeight: Double,
    val candidateReliability: Double,
    val evidenceSufficient: Boolean,
    val policyFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(observationFingerprints == observationFingerprints.distinct().sorted())
        require(observationFingerprints.all { it.matches(Regex("[0-9a-f]{64}")) })
        require(verifiedDirectionalObservations >= 0)
        require(supportedWeight.isFinite() && supportedWeight >= 0.0)
        require(contradictedWeight.isFinite() && contradictedWeight >= 0.0)
        require(candidateReliability.isFinite() && candidateReliability in 0.0..1.0)
        require(policyFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(
            fingerprint == profileFingerprint(
                origin = origin,
                observationFingerprints = observationFingerprints,
                verifiedDirectionalObservations = verifiedDirectionalObservations,
                supportedWeight = supportedWeight,
                contradictedWeight = contradictedWeight,
                candidateReliability = candidateReliability,
                evidenceSufficient = evidenceSufficient,
                policyFingerprint = policyFingerprint,
            )
        )
    }

    val trustAuthority: Boolean
        get() = false

    val routingAuthority: Boolean
        get() = false

    val promotionAllowed: Boolean
        get() = false

    val activationAllowed: Boolean
        get() = false
}

/**
 * B396 learns a candidate Web-origin reliability estimate from explicitly verified claim outcomes.
 *
 * It never converts Web text into truth, never treats an unverified observation as learning
 * evidence, and never mutates DeepSearch/provider reliability directly. The returned profile is a
 * reviewable candidate only; promotion/routing remains a separate later decision.
 */
class WebSourceReliabilityLearner {
    fun learn(
        origin: WebOriginIdentity,
        observations: Collection<WebReliabilityObservation>,
        policy: WebSourceReliabilityPolicy = WebSourceReliabilityPolicy(),
    ): WebSourceReliabilityProfile {
        require(observations.map { it.fingerprint }.distinct().size == observations.size) {
            "Duplicate Web reliability observations are not allowed"
        }
        observations.forEach { observation ->
            require(observation.resource.origin == origin) {
                "Web reliability observation belongs to another origin"
            }
        }

        val canonical = observations.sortedBy { it.fingerprint }
        val eligible = canonical.filter(WebReliabilityObservation::learningEligible)
        val supportedWeight = eligible
            .filter { it.verdict == WebReliabilityVerdict.SUPPORTED }
            .sumOf { it.weight }
        val contradictedWeight = eligible
            .filter { it.verdict == WebReliabilityVerdict.CONTRADICTED }
            .sumOf { it.weight }
        val denominator =
            policy.priorSupportedWeight +
                policy.priorContradictedWeight +
                supportedWeight +
                contradictedWeight
        val candidateReliability =
            (policy.priorSupportedWeight + supportedWeight) / denominator
        val verifiedDirectionalObservations = eligible.size
        val evidenceSufficient =
            verifiedDirectionalObservations >= policy.minimumVerifiedDirectionalObservations
        val observationFingerprints = canonical.map { it.fingerprint }
        val policyFingerprint = policy.fingerprint()
        val fingerprint = profileFingerprint(
            origin = origin,
            observationFingerprints = observationFingerprints,
            verifiedDirectionalObservations = verifiedDirectionalObservations,
            supportedWeight = supportedWeight,
            contradictedWeight = contradictedWeight,
            candidateReliability = candidateReliability,
            evidenceSufficient = evidenceSufficient,
            policyFingerprint = policyFingerprint,
        )
        return WebSourceReliabilityProfile(
            origin = origin,
            observationFingerprints = observationFingerprints,
            verifiedDirectionalObservations = verifiedDirectionalObservations,
            supportedWeight = supportedWeight,
            contradictedWeight = contradictedWeight,
            candidateReliability = candidateReliability,
            evidenceSufficient = evidenceSufficient,
            policyFingerprint = policyFingerprint,
            fingerprint = fingerprint,
        )
    }
}

private fun observationFingerprint(
    resource: WebResourceIdentity,
    claimCandidateId: String,
    sourceDocumentFingerprint: String,
    verificationEvidenceFingerprint: String,
    verified: Boolean,
    verdict: WebReliabilityVerdict,
    weight: Double,
): String = reliabilityFingerprint(
    "web-reliability-observation/v1",
    resource.origin.id.value,
    resource.id.value,
    claimCandidateId,
    sourceDocumentFingerprint,
    verificationEvidenceFingerprint,
    verified.toString(),
    verdict.name,
    java.lang.Double.toHexString(weight),
)

private fun profileFingerprint(
    origin: WebOriginIdentity,
    observationFingerprints: List<String>,
    verifiedDirectionalObservations: Int,
    supportedWeight: Double,
    contradictedWeight: Double,
    candidateReliability: Double,
    evidenceSufficient: Boolean,
    policyFingerprint: String,
): String = reliabilityFingerprint(
    "web-source-reliability-profile/v1",
    origin.id.value,
    origin.canonicalOrigin,
    verifiedDirectionalObservations.toString(),
    java.lang.Double.toHexString(supportedWeight),
    java.lang.Double.toHexString(contradictedWeight),
    java.lang.Double.toHexString(candidateReliability),
    evidenceSufficient.toString(),
    policyFingerprint,
    *observationFingerprints.sorted().toTypedArray(),
)

private fun reliabilityFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
