package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.level7.EvidenceActionKind

class MetaInformationActionBridge {
    fun candidates(
        inference: MetaInferenceResult,
    ): List<InformationActionCandidate> {
        if (!inference.informationRequired) return emptyList()

        val unresolved = inference.identifiability?.unresolvedPairs.orEmpty()
        if (unresolved.isEmpty()) {
            val kind = when (inference.status) {
                MetaInferenceStatus.MODEL_INCONSISTENT -> EvidenceActionKind.SOURCE_REFRESH
                MetaInferenceStatus.BUDGET_EXHAUSTED -> EvidenceActionKind.LOCAL_RETRIEVAL
                MetaInferenceStatus.INFORMATION_REQUIRED -> EvidenceActionKind.ASK_USER
                MetaInferenceStatus.DISTINCT,
                MetaInferenceStatus.CONFLICT -> return emptyList()
            }
            return listOf(
                InformationActionCandidate.create(
                    interventionId = "meta:${inference.status.name.lowercase()}",
                    kind = kind,
                    expectedInformationGainMicros = 800_000L,
                    resourceCostMicros = 100_000L,
                    privacyCostMicros =
                        if (kind == EvidenceActionKind.ASK_USER) 100_000L else 40_000L,
                    riskCostMicros = 20_000L,
                    reversibilityMicros = INFORMATION_SCORE_SCALE,
                    rationale = rationale(inference.status),
                )
            )
        }

        return unresolved.flatMap { pair ->
            val pairFingerprint = StableFieldIds.fingerprint(
                "meta-unresolved-pair/v1",
                inference.fingerprint,
                pair.firstId,
                pair.secondId,
            )
            listOf(
                InformationActionCandidate.create(
                    interventionId = "meta-memory:$pairFingerprint",
                    kind = EvidenceActionKind.MEMORY_LOOKUP,
                    expectedInformationGainMicros = 700_000L,
                    resourceCostMicros = 80_000L,
                    privacyCostMicros = 40_000L,
                    riskCostMicros = 10_000L,
                    reversibilityMicros = INFORMATION_SCORE_SCALE,
                    rationale =
                        "Prüfe lokale Evidenz, um zwei derzeit nicht unterscheidbare Kandidaten zu trennen.",
                ),
                InformationActionCandidate.create(
                    interventionId = "meta-owner:$pairFingerprint",
                    kind = EvidenceActionKind.ASK_USER,
                    expectedInformationGainMicros = 900_000L,
                    resourceCostMicros = 40_000L,
                    privacyCostMicros = 100_000L,
                    riskCostMicros = 20_000L,
                    reversibilityMicros = INFORMATION_SCORE_SCALE,
                    rationale =
                        "Frage gezielt nach dem Merkmal, das die verbleibenden Kandidaten unterscheiden kann.",
                ),
            )
        }
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
    }

    private fun rationale(status: MetaInferenceStatus): String = when (status) {
        MetaInferenceStatus.MODEL_INCONSISTENT ->
            "Aktualisiere die lokale Quelle, weil Beobachtung und Strukturmodell nicht zusammenpassen."
        MetaInferenceStatus.BUDGET_EXHAUSTED ->
            "Lade gezielt zusätzliche lokale Evidenz, statt die Analyse unbeschränkt zu vergrößern."
        MetaInferenceStatus.INFORMATION_REQUIRED ->
            "Hole genau die fehlende Information ein, die die Kandidaten unterscheiden kann."
        MetaInferenceStatus.DISTINCT,
        MetaInferenceStatus.CONFLICT ->
            error("Resolved inference cannot request information here")
    }
}
