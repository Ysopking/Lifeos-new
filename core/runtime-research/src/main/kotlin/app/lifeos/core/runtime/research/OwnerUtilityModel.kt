package app.lifeos.core.runtime.research

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class OwnerUtilityPolarity {
    MAXIMIZE,
    MINIMIZE,
}

enum class OwnerUtilityDimension(
    val polarity: OwnerUtilityPolarity,
) {
    OWNER_GOAL_UTILITY(OwnerUtilityPolarity.MAXIMIZE),
    CORRECTNESS(OwnerUtilityPolarity.MAXIMIZE),
    ROBUSTNESS(OwnerUtilityPolarity.MAXIMIZE),
    LEARNING_VALUE(OwnerUtilityPolarity.MAXIMIZE),
    REVERSIBILITY(OwnerUtilityPolarity.MAXIMIZE),
    EFFORT_COST(OwnerUtilityPolarity.MINIMIZE),
    RISK_COST(OwnerUtilityPolarity.MINIMIZE),
    EXTERNAL_EFFECT_COST(OwnerUtilityPolarity.MINIMIZE),
    RESOURCE_COST(OwnerUtilityPolarity.MINIMIZE),
    UNCERTAINTY_COST(OwnerUtilityPolarity.MINIMIZE),
}

enum class OwnerUtilityEvidenceKind {
    EXPLICIT_DECLARATION,
    EXPLICIT_FEEDBACK,
    EXPLICIT_PRIORITY,
}

data class OwnerUtilityPreferenceObservation(
    val id: String,
    val dimension: OwnerUtilityDimension,
    val importance: Double,
    val confidence: Double,
    val evidenceKind: OwnerUtilityEvidenceKind,
    val sourceFingerprint: String,
    val ownerConfirmed: Boolean,
    val fingerprint: String,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(importance.isFinite() && importance in 0.0..1.0)
        require(confidence.isFinite() && confidence > 0.0 && confidence <= 1.0)
        require(sourceFingerprint.matches(SHA_256_REGEX))
        require(ownerConfirmed) {
            "B386 accepts explicit owner-confirmed preference evidence only"
        }
        require(
            fingerprint == observationFingerprint(
                dimension = dimension,
                importance = importance,
                confidence = confidence,
                evidenceKind = evidenceKind,
                sourceFingerprint = sourceFingerprint,
                ownerConfirmed = ownerConfirmed,
            )
        )
        require(id == ID_PREFIX + fingerprint)
    }

    companion object {
        const val ID_PREFIX = "owner-utility-observation:"

        fun create(
            dimension: OwnerUtilityDimension,
            importance: Double,
            confidence: Double,
            evidenceKind: OwnerUtilityEvidenceKind,
            sourceFingerprint: String,
            ownerConfirmed: Boolean = true,
        ): OwnerUtilityPreferenceObservation {
            require(ownerConfirmed) {
                "B386 accepts explicit owner-confirmed preference evidence only"
            }
            val fingerprint = observationFingerprint(
                dimension = dimension,
                importance = importance,
                confidence = confidence,
                evidenceKind = evidenceKind,
                sourceFingerprint = sourceFingerprint,
                ownerConfirmed = ownerConfirmed,
            )
            return OwnerUtilityPreferenceObservation(
                id = ID_PREFIX + fingerprint,
                dimension = dimension,
                importance = importance,
                confidence = confidence,
                evidenceKind = evidenceKind,
                sourceFingerprint = sourceFingerprint,
                ownerConfirmed = ownerConfirmed,
                fingerprint = fingerprint,
            )
        }
    }
}

data class OwnerUtilityPreference(
    val dimension: OwnerUtilityDimension,
    val polarity: OwnerUtilityPolarity,
    val meanImportance: Double,
    val evidenceCount: Int,
    val effectiveEvidenceWeight: Double,
    val evidenceIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(polarity == dimension.polarity)
        require(meanImportance.isFinite() && meanImportance in 0.0..1.0)
        require(evidenceCount > 0)
        require(effectiveEvidenceWeight.isFinite() && effectiveEvidenceWeight > 0.0)
        require(evidenceIds.size == evidenceCount)
        require(evidenceIds == evidenceIds.distinct().sorted())
        require(
            fingerprint == preferenceFingerprint(
                dimension = dimension,
                meanImportance = meanImportance,
                effectiveEvidenceWeight = effectiveEvidenceWeight,
                evidenceIds = evidenceIds,
            )
        )
    }
}

data class OwnerUtilityProfile(
    val preferences: List<OwnerUtilityPreference>,
    val unobservedDimensions: List<OwnerUtilityDimension>,
    val sourceObservationIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(preferences == preferences.sortedBy { it.dimension.ordinal })
        require(preferences.map { it.dimension }.distinct().size == preferences.size)
        require(unobservedDimensions == unobservedDimensions.distinct().sortedBy { it.ordinal })
        require(
            preferences.map { it.dimension }.toSet()
                .intersect(unobservedDimensions.toSet())
                .isEmpty()
        )
        require(
            preferences.map { it.dimension }.toSet() +
                unobservedDimensions.toSet() == OwnerUtilityDimension.entries.toSet()
        )
        require(sourceObservationIds == sourceObservationIds.distinct().sorted())
        require(
            fingerprint == profileFingerprint(
                preferences = preferences,
                unobservedDimensions = unobservedDimensions,
                sourceObservationIds = sourceObservationIds,
            )
        )
    }

    val decisionAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    val ownerPolicyAuthority: Boolean
        get() = false

    val promotionAuthority: Boolean
        get() = false

    fun preference(
        dimension: OwnerUtilityDimension,
    ): OwnerUtilityPreference? =
        preferences.firstOrNull { it.dimension == dimension }
}

/**
 * B386 learns only reviewable owner-utility preference weights from explicit owner-confirmed
 * evidence. It does not infer preferences from passive behavior, does not choose an action, and
 * cannot weaken Owner Policy or any external-effect gate.
 */
class OwnerUtilityModel {
    fun learn(
        observations: Collection<OwnerUtilityPreferenceObservation>,
    ): OwnerUtilityProfile {
        val canonical = canonicalize(observations)
        val preferences = canonical
            .groupBy { it.dimension }
            .toSortedMap(compareBy { it.ordinal })
            .map { (dimension, evidence) ->
                val totalWeight = evidence.sumOf { it.confidence }
                val weightedImportance =
                    evidence.sumOf { it.importance * it.confidence } / totalWeight
                val ids = evidence.map { it.id }.distinct().sorted()
                OwnerUtilityPreference(
                    dimension = dimension,
                    polarity = dimension.polarity,
                    meanImportance = weightedImportance,
                    evidenceCount = ids.size,
                    effectiveEvidenceWeight = totalWeight,
                    evidenceIds = ids,
                    fingerprint = preferenceFingerprint(
                        dimension = dimension,
                        meanImportance = weightedImportance,
                        effectiveEvidenceWeight = totalWeight,
                        evidenceIds = ids,
                    ),
                )
            }

        val observed = preferences.map { it.dimension }.toSet()
        val unobserved = OwnerUtilityDimension.entries
            .filterNot(observed::contains)
            .sortedBy { it.ordinal }
        val sourceIds = canonical.map { it.id }.sorted()

        return OwnerUtilityProfile(
            preferences = preferences,
            unobservedDimensions = unobserved,
            sourceObservationIds = sourceIds,
            fingerprint = profileFingerprint(
                preferences = preferences,
                unobservedDimensions = unobserved,
                sourceObservationIds = sourceIds,
            ),
        )
    }

    private fun canonicalize(
        observations: Collection<OwnerUtilityPreferenceObservation>,
    ): List<OwnerUtilityPreferenceObservation> {
        val grouped = observations.groupBy { it.id }
        grouped.forEach { (id, items) ->
            require(items.all { it == items.first() }) {
                "Conflicting owner-utility observation identity: $id"
            }
        }
        return grouped.values
            .map { it.first() }
            .sortedBy { it.id }
    }
}

private fun observationFingerprint(
    dimension: OwnerUtilityDimension,
    importance: Double,
    confidence: Double,
    evidenceKind: OwnerUtilityEvidenceKind,
    sourceFingerprint: String,
    ownerConfirmed: Boolean,
): String = ownerUtilityFingerprint(
    "owner-utility-observation/v1",
    dimension.name,
    dimension.polarity.name,
    java.lang.Double.toHexString(importance),
    java.lang.Double.toHexString(confidence),
    evidenceKind.name,
    sourceFingerprint,
    ownerConfirmed.toString(),
)

private fun preferenceFingerprint(
    dimension: OwnerUtilityDimension,
    meanImportance: Double,
    effectiveEvidenceWeight: Double,
    evidenceIds: List<String>,
): String = ownerUtilityFingerprint(
    "owner-utility-preference/v1",
    dimension.name,
    dimension.polarity.name,
    java.lang.Double.toHexString(meanImportance),
    java.lang.Double.toHexString(effectiveEvidenceWeight),
    *evidenceIds.toTypedArray(),
)

private fun profileFingerprint(
    preferences: List<OwnerUtilityPreference>,
    unobservedDimensions: List<OwnerUtilityDimension>,
    sourceObservationIds: List<String>,
): String = ownerUtilityFingerprint(
    "owner-utility-profile/v1",
    preferences.joinToString("\u001f") { it.fingerprint },
    unobservedDimensions.joinToString("\u001f") { it.name },
    *sourceObservationIds.toTypedArray(),
)

private fun ownerUtilityFingerprint(
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

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
