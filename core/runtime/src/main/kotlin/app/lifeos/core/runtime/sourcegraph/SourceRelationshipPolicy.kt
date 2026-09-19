package app.lifeos.core.runtime.sourcegraph

data class SourceRelationshipPolicyDecision(
    val state: SourceRelationshipState,
    val confidence: Double,
    val positiveEvidence: List<SourceRelationshipEvidence>,
    val negativeEvidence: List<SourceRelationshipEvidence>,
    val blockers: List<String>,
    val independentEvidenceFamilies: Set<RelationshipEvidenceFamily>,
    val rationale: String,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(blockers == blockers.distinct().sorted())
        require(rationale.isNotBlank())
    }
}

/**
 * Evidence-class policy. It deliberately never sums arbitrary weights: deterministic evidence can
 * confirm or block, while non-deterministic merge decisions require independent evidence families.
 */
class SourceRelationshipPolicy {
    fun evaluate(
        type: SourceRelationshipType,
        evidence: Collection<SourceRelationshipEvidence>,
        explicitBlockers: Collection<String> = emptyList(),
    ): SourceRelationshipPolicyDecision {
        val canonical = deduplicateLineage(evidence)
        val positive = canonical
            .filter { it.polarity == EvidencePolarity.POSITIVE }
            .sortedBy { it.evidenceId }
        val negative = canonical
            .filter { it.polarity == EvidencePolarity.NEGATIVE }
            .sortedBy { it.evidenceId }

        val blockers = buildList {
            addAll(explicitBlockers.filter { it.isNotBlank() })
            negative
                .filter {
                    it.strength == EvidenceStrength.DETERMINISTIC ||
                        it.kind == RelationshipEvidenceKind.USER_SEPARATION
                }
                .forEach { add("negative:" + it.kind.name.lowercase()) }
        }.distinct().sorted()

        val families = canonical
            .groupBy { it.independenceKey }
            .values
            .map { it.first().family }
            .toSortedSet(compareBy { it.name })

        if (blockers.isNotEmpty()) {
            return SourceRelationshipPolicyDecision(
                state = SourceRelationshipState.BLOCKED,
                confidence = blockingConfidence(negative),
                positiveEvidence = positive,
                negativeEvidence = negative,
                blockers = blockers,
                independentEvidenceFamilies = families,
                rationale = "deterministic-or-explicit-blocker",
            )
        }

        val deterministicPositive = positive.filter { it.strength == EvidenceStrength.DETERMINISTIC }
        if (deterministicPositive.isNotEmpty()) {
            return SourceRelationshipPolicyDecision(
                state = SourceRelationshipState.CONFIRMED,
                confidence = deterministicPositive.maxOf { it.confidence },
                positiveEvidence = positive,
                negativeEvidence = negative,
                blockers = emptyList(),
                independentEvidenceFamilies = families,
                rationale = "deterministic-positive-evidence",
            )
        }

        if (type.mergeSensitive) {
            return evaluateMergeSensitive(type, positive, negative, families)
        }

        val strong = positive.filter { it.strength == EvidenceStrength.STRONG }
        if (strong.isNotEmpty()) {
            return SourceRelationshipPolicyDecision(
                state = SourceRelationshipState.CONFIRMED,
                confidence = strong.maxOf { it.confidence },
                positiveEvidence = positive,
                negativeEvidence = negative,
                blockers = emptyList(),
                independentEvidenceFamilies = families,
                rationale = "strong-positive-evidence",
            )
        }

        return SourceRelationshipPolicyDecision(
            state = SourceRelationshipState.CANDIDATE,
            confidence = positive.maxOfOrNull { it.confidence } ?: 0.0,
            positiveEvidence = positive,
            negativeEvidence = negative,
            blockers = emptyList(),
            independentEvidenceFamilies = families,
            rationale = if (positive.isEmpty()) "no-positive-evidence" else "supporting-or-weak-evidence-only",
        )
    }

    private fun evaluateMergeSensitive(
        type: SourceRelationshipType,
        positive: List<SourceRelationshipEvidence>,
        negative: List<SourceRelationshipEvidence>,
        families: Set<RelationshipEvidenceFamily>,
    ): SourceRelationshipPolicyDecision {
        val strong = positive.filter { it.strength == EvidenceStrength.STRONG }
        val independentStrong = independentByFamily(strong)
        val independentPositive = independentByFamily(
            positive.filter { it.strength != EvidenceStrength.WEAK }
        )

        val eligible = when (type) {
            SourceRelationshipType.SAME_PERSON ->
                strong.any { it.kind in PERSON_IDENTITY_KINDS } &&
                    independentStrong.size >= 2 &&
                    independentPositive.size >= 2

            SourceRelationshipType.SAME_PROJECT ->
                independentStrong.size >= 2 &&
                    independentPositive.size >= 3 &&
                    independentPositive.keys.any {
                        it == RelationshipEvidenceFamily.OPERATIONAL ||
                            it == RelationshipEvidenceFamily.CONTEXT
                    } &&
                    independentPositive.keys.any {
                        it == RelationshipEvidenceFamily.SEMANTIC ||
                            it == RelationshipEvidenceFamily.ENTITY
                    }

            else ->
                independentStrong.size >= 2 &&
                    independentPositive.size >= 3
        }

        if (!eligible) {
            return SourceRelationshipPolicyDecision(
                state = SourceRelationshipState.CANDIDATE,
                confidence = positive.maxOfOrNull { it.confidence } ?: 0.0,
                positiveEvidence = positive,
                negativeEvidence = negative,
                blockers = emptyList(),
                independentEvidenceFamilies = families,
                rationale = "insufficient-independent-evidence-families",
            )
        }

        val required = independentStrong.values
            .map { evidence -> evidence.maxOf { it.confidence } }
            .sortedDescending()
            .take(2)
        val confidence = required.minOrNull() ?: 0.0
        return SourceRelationshipPolicyDecision(
            state = SourceRelationshipState.MERGE_ELIGIBLE,
            confidence = confidence,
            positiveEvidence = positive,
            negativeEvidence = negative,
            blockers = emptyList(),
            independentEvidenceFamilies = families,
            rationale = "independent-strong-evidence-threshold",
        )
    }

    private fun deduplicateLineage(
        evidence: Collection<SourceRelationshipEvidence>,
    ): List<SourceRelationshipEvidence> =
        evidence
            .groupBy {
                listOf(
                    it.family.name,
                    it.kind.name,
                    it.polarity.name,
                    it.lineageRoot.photonId.value,
                    it.lineageRoot.revision.toString(),
                ).joinToString("|")
            }
            .values
            .map { group ->
                group.maxWith(
                    compareBy<SourceRelationshipEvidence>(
                        { strengthRank(it.strength) },
                        { it.confidence },
                        { it.evidenceId },
                    )
                )
            }
            .sortedBy { it.evidenceId }

    private fun independentByFamily(
        evidence: Collection<SourceRelationshipEvidence>,
    ): Map<RelationshipEvidenceFamily, List<SourceRelationshipEvidence>> =
        evidence
            .groupBy { it.family }
            .mapValues { (_, familyEvidence) ->
                familyEvidence
                    .groupBy { it.lineageRoot }
                    .values
                    .map { lineage ->
                        lineage.maxWith(
                            compareBy<SourceRelationshipEvidence>(
                                { strengthRank(it.strength) },
                                { it.confidence },
                                { it.evidenceId },
                            )
                        )
                    }
            }

    private fun blockingConfidence(
        negative: List<SourceRelationshipEvidence>,
    ): Double =
        negative
            .filter {
                it.strength == EvidenceStrength.DETERMINISTIC ||
                    it.kind == RelationshipEvidenceKind.USER_SEPARATION
            }
            .maxOfOrNull { it.confidence }
            ?: 1.0

    private fun strengthRank(strength: EvidenceStrength): Int = when (strength) {
        EvidenceStrength.WEAK -> 0
        EvidenceStrength.SUPPORTING -> 1
        EvidenceStrength.STRONG -> 2
        EvidenceStrength.DETERMINISTIC -> 3
    }

    private companion object {
        val PERSON_IDENTITY_KINDS = setOf(
            RelationshipEvidenceKind.EMAIL,
            RelationshipEvidenceKind.PHONE,
            RelationshipEvidenceKind.CONTACT_ID,
            RelationshipEvidenceKind.ACCOUNT_ID,
            RelationshipEvidenceKind.ACTOR_ID,
            RelationshipEvidenceKind.USER_CONFIRMATION,
        )
    }
}
