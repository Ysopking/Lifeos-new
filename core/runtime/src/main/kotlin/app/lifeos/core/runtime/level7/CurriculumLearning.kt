package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

data class PredictionFailure(
    val predictionId: String,
    val expectedFingerprint: String,
    val observedFingerprint: String,
    val failureClass: String,
    val evidenceFingerprint: String,
) {
    init {
        require(predictionId.isNotBlank())
        require(expectedFingerprint.isNotBlank())
        require(observedFingerprint.isNotBlank())
        require(expectedFingerprint != observedFingerprint)
        require(failureClass.isNotBlank())
        require(evidenceFingerprint.isNotBlank())
    }
}

data class CurriculumCandidate private constructor(
    val id: String,
    val generatorId: String,
    val evaluatorId: String,
    val failureFingerprints: List<String>,
    val curriculumFingerprint: String,
) {
    init {
        require(generatorId.isNotBlank())
        require(evaluatorId.isNotBlank())
        require(generatorId != evaluatorId) {
            "Curriculum generator and evaluator must be separated"
        }
        require(failureFingerprints.isNotEmpty())
        require(curriculumFingerprint.isNotBlank())
        require(id == expectedId())
    }

    val strategyPromotionAllowed: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-curriculum-candidate/v1",
        generatorId,
        evaluatorId,
        curriculumFingerprint,
        *failureFingerprints.sorted().toTypedArray(),
    )

    private fun expectedId(): String = "curriculum:${fingerprint()}"

    companion object {
        fun create(
            generatorId: String,
            evaluatorId: String,
            failures: Collection<PredictionFailure>,
            curriculumFingerprint: String,
        ): CurriculumCandidate {
            val fps = failures.map {
                StableFieldIds.fingerprint(
                    "level7-prediction-failure/v1",
                    it.predictionId,
                    it.expectedFingerprint,
                    it.observedFingerprint,
                    it.failureClass,
                    it.evidenceFingerprint,
                )
            }.distinct().sorted()
            require(fps.isNotEmpty())
            val fp = StableFieldIds.fingerprint(
                "level7-curriculum-candidate/v1",
                generatorId,
                evaluatorId,
                curriculumFingerprint,
                *fps.toTypedArray(),
            )
            return CurriculumCandidate(
                id = "curriculum:$fp",
                generatorId = generatorId,
                evaluatorId = evaluatorId,
                failureFingerprints = fps,
                curriculumFingerprint = curriculumFingerprint,
            )
        }
    }
}
