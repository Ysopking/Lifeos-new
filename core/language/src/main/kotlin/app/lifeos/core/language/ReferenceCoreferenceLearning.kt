package app.lifeos.core.language

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class ReferenceLearningEvidenceKind {
    OWNER_CONFIRMED_RESOLUTION,
    OWNER_CORRECTED_RESOLUTION,
}

data class ReferenceHabitObservation(
    val episodeFingerprint: String,
    val episodeStatus: LanguageLearningEpisodeStatus,
    val sourceCycleId: String,
    val discoursePatternFingerprint: String,
    val referenceSurface: String,
    val normalizedReference: String,
    val targetKind: String,
    val targetEvidenceFingerprint: String,
    val evidenceKind: ReferenceLearningEvidenceKind,
    val sourceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(episodeFingerprint.matches(SHA_256_REGEX_B425))
        require(
            episodeStatus == LanguageLearningEpisodeStatus.OWNER_CONFIRMED ||
                episodeStatus == LanguageLearningEpisodeStatus.OWNER_CORRECTED
        ) {
            "B425 personal reference learning requires explicit owner feedback"
        }
        require(sourceCycleId.isNotBlank())
        require(discoursePatternFingerprint.matches(SHA_256_REGEX_B425))
        require(referenceSurface.isNotBlank() && referenceSurface.length <= MAX_REFERENCE_CHARS)
        require(referenceSurface.none { it == '\u0000' || it == '\r' || it == '\n' })
        require(normalizedReference == normalizeReference(referenceSurface))
        require(targetKind.isNotBlank() && targetKind.length <= MAX_TARGET_KIND_CHARS)
        require(targetEvidenceFingerprint.matches(SHA_256_REGEX_B425))
        require(sourceFingerprint.matches(SHA_256_REGEX_B425))
        when (evidenceKind) {
            ReferenceLearningEvidenceKind.OWNER_CONFIRMED_RESOLUTION ->
                require(episodeStatus == LanguageLearningEpisodeStatus.OWNER_CONFIRMED)
            ReferenceLearningEvidenceKind.OWNER_CORRECTED_RESOLUTION ->
                require(episodeStatus == LanguageLearningEpisodeStatus.OWNER_CORRECTED)
        }
        require(
            fingerprint == referenceObservationFingerprint(
                episodeFingerprint,
                episodeStatus,
                sourceCycleId,
                discoursePatternFingerprint,
                referenceSurface,
                normalizedReference,
                targetKind,
                targetEvidenceFingerprint,
                evidenceKind,
                sourceFingerprint,
            )
        )
    }

    val resolutionAuthority: Boolean get() = false
    val contextMutationAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            episode: LanguageLearningEpisode,
            discoursePattern: DiscoursePatternCandidate,
            referenceSurface: String,
            targetKind: String,
            targetEvidenceFingerprint: String,
            evidenceKind: ReferenceLearningEvidenceKind,
            sourceFingerprint: String,
        ): ReferenceHabitObservation {
            require(discoursePattern.scope == LexicalLearningScope.OWNER_LANGUAGE) {
                "B425 personal reference habits require OWNER_LANGUAGE discourse evidence"
            }
            require(
                discoursePattern.supportingEpisodeFingerprints.contains(episode.fingerprint)
            ) {
                "B425 reference observation must bind an episode supporting the B424 pattern"
            }
            val surface = referenceSurface.trim()
            val normalized = normalizeReference(surface)
            return ReferenceHabitObservation(
                episodeFingerprint = episode.fingerprint,
                episodeStatus = episode.status,
                sourceCycleId = episode.sourceCycleId,
                discoursePatternFingerprint = discoursePattern.fingerprint,
                referenceSurface = surface,
                normalizedReference = normalized,
                targetKind = targetKind.trim().lowercase(Locale.ROOT),
                targetEvidenceFingerprint = targetEvidenceFingerprint,
                evidenceKind = evidenceKind,
                sourceFingerprint = sourceFingerprint,
                fingerprint = referenceObservationFingerprint(
                    episode.fingerprint,
                    episode.status,
                    episode.sourceCycleId,
                    discoursePattern.fingerprint,
                    surface,
                    normalized,
                    targetKind.trim().lowercase(Locale.ROOT),
                    targetEvidenceFingerprint,
                    evidenceKind,
                    sourceFingerprint,
                ),
            )
        }
    }
}

data class ReferenceHabitCandidate(
    val normalizedReference: String,
    val discoursePatternFingerprint: String,
    val targetKind: String,
    val supportingEpisodeFingerprints: List<String>,
    val supportingCycleIds: List<String>,
    val targetEvidenceFingerprints: List<String>,
    val observationFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(normalizedReference.isNotBlank())
        require(discoursePatternFingerprint.matches(SHA_256_REGEX_B425))
        require(targetKind.isNotBlank())
        require(supportingEpisodeFingerprints.size >= 2)
        require(
            supportingEpisodeFingerprints ==
                supportingEpisodeFingerprints.distinct().sorted()
        )
        require(supportingCycleIds.size >= 2)
        require(supportingCycleIds == supportingCycleIds.distinct().sorted())
        require(
            targetEvidenceFingerprints ==
                targetEvidenceFingerprints.distinct().sorted()
        )
        require(targetEvidenceFingerprints.all { it.matches(SHA_256_REGEX_B425) })
        require(observationFingerprints == observationFingerprints.distinct().sorted())
        require(
            fingerprint == referenceCandidateFingerprint(
                normalizedReference,
                discoursePatternFingerprint,
                targetKind,
                supportingEpisodeFingerprints,
                supportingCycleIds,
                targetEvidenceFingerprints,
                observationFingerprints,
            )
        )
    }

    val directResolutionAllowed: Boolean get() = false
    val referenceAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B425 learns personal reference/coreference habits from explicit owner-confirmed or corrected
 * resolutions only.
 *
 * A candidate can bias later shadow evaluation, but it never resolves a live reference, mutates
 * discourse state, promotes itself or executes an action. Competing target-kind candidates remain
 * explicit instead of being collapsed into a hidden winner.
 */
class ReferenceCoreferenceLearningEngine(
    private val minimumIndependentCycles: Int = 2,
) {
    init {
        require(minimumIndependentCycles in 2..16)
    }

    fun induce(
        observations: Collection<ReferenceHabitObservation>,
    ): List<ReferenceHabitCandidate> {
        if (observations.isEmpty()) return emptyList()

        val canonical = observations
            .groupBy { it.fingerprint }
            .map { (_, same) ->
                require(same.all { it == same.first() }) {
                    "Conflicting reference-learning observation identity"
                }
                same.first()
            }
            .sortedBy { it.fingerprint }

        return canonical
            .groupBy {
                Triple(
                    it.normalizedReference,
                    it.discoursePatternFingerprint,
                    it.targetKind,
                )
            }
            .mapNotNull { (_, grouped) ->
                val independent = grouped
                    .groupBy { it.sourceCycleId }
                    .map { (_, sameCycle) -> sameCycle.minBy { it.fingerprint } }
                    .sortedBy { it.fingerprint }
                if (independent.size < minimumIndependentCycles) {
                    return@mapNotNull null
                }

                val episodes =
                    independent.map { it.episodeFingerprint }.distinct().sorted()
                val cycles =
                    independent.map { it.sourceCycleId }.distinct().sorted()
                val targetEvidence =
                    independent.map { it.targetEvidenceFingerprint }.distinct().sorted()
                val observationFingerprints =
                    independent.map { it.fingerprint }.distinct().sorted()
                val first = independent.first()

                ReferenceHabitCandidate(
                    normalizedReference = first.normalizedReference,
                    discoursePatternFingerprint = first.discoursePatternFingerprint,
                    targetKind = first.targetKind,
                    supportingEpisodeFingerprints = episodes,
                    supportingCycleIds = cycles,
                    targetEvidenceFingerprints = targetEvidence,
                    observationFingerprints = observationFingerprints,
                    fingerprint = referenceCandidateFingerprint(
                        first.normalizedReference,
                        first.discoursePatternFingerprint,
                        first.targetKind,
                        episodes,
                        cycles,
                        targetEvidence,
                        observationFingerprints,
                    ),
                )
            }
            .sortedBy { it.fingerprint }
    }
}

private fun normalizeReference(value: String): String =
    value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun referenceObservationFingerprint(
    episodeFingerprint: String,
    episodeStatus: LanguageLearningEpisodeStatus,
    sourceCycleId: String,
    discoursePatternFingerprint: String,
    referenceSurface: String,
    normalizedReference: String,
    targetKind: String,
    targetEvidenceFingerprint: String,
    evidenceKind: ReferenceLearningEvidenceKind,
    sourceFingerprint: String,
): String = b425Fingerprint(
    "reference-habit-observation/v1",
    episodeFingerprint,
    episodeStatus.name,
    sourceCycleId,
    discoursePatternFingerprint,
    referenceSurface,
    normalizedReference,
    targetKind,
    targetEvidenceFingerprint,
    evidenceKind.name,
    sourceFingerprint,
)

private fun referenceCandidateFingerprint(
    normalizedReference: String,
    discoursePatternFingerprint: String,
    targetKind: String,
    supportingEpisodeFingerprints: List<String>,
    supportingCycleIds: List<String>,
    targetEvidenceFingerprints: List<String>,
    observationFingerprints: List<String>,
): String = b425Fingerprint(
    "reference-habit-candidate/v1",
    normalizedReference,
    discoursePatternFingerprint,
    targetKind,
    supportingEpisodeFingerprints.joinToString("\u001f"),
    supportingCycleIds.joinToString("\u001f"),
    targetEvidenceFingerprints.joinToString("\u001f"),
    observationFingerprints.joinToString("\u001f"),
)

private fun b425Fingerprint(domain: String, vararg parts: String): String {
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

private val SHA_256_REGEX_B425 = Regex("[0-9a-f]{64}")
private const val MAX_REFERENCE_CHARS = 160
private const val MAX_TARGET_KIND_CHARS = 96
