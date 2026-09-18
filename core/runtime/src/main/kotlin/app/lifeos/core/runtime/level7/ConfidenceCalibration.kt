package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

@JvmInline
value class RawConfidence(val value: Double) {
    init {
        require(value.isFinite() && value in 0.0..1.0)
    }
}

@JvmInline
value class CalibratedConfidence(val value: Double) {
    init {
        require(value.isFinite() && value in 0.0..1.0)
    }
}

data class ConfidenceCalibrationRecord(
    val sourceId: String,
    val raw: RawConfidence,
    val calibrated: CalibratedConfidence,
    val calibrationModelFingerprint: String,
    val evidenceFingerprint: String,
) {
    init {
        require(sourceId.isNotBlank())
        require(calibrationModelFingerprint.isNotBlank())
        require(evidenceFingerprint.isNotBlank())
    }

    val rawConfidencePreserved: Boolean get() = true
    val truthScoreExposed: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-confidence-calibration/v1",
        sourceId,
        java.lang.Double.toHexString(raw.value),
        java.lang.Double.toHexString(calibrated.value),
        calibrationModelFingerprint,
        evidenceFingerprint,
    )
}

object ConfidenceCalibrator {
    fun calibrate(
        sourceId: String,
        raw: RawConfidence,
        calibrationModelFingerprint: String,
        evidenceFingerprint: String,
        transform: (Double) -> Double,
    ): ConfidenceCalibrationRecord {
        val calibrated = transform(raw.value).coerceIn(0.0, 1.0)
        return ConfidenceCalibrationRecord(
            sourceId = sourceId,
            raw = raw,
            calibrated = CalibratedConfidence(calibrated),
            calibrationModelFingerprint = calibrationModelFingerprint,
            evidenceFingerprint = evidenceFingerprint,
        )
    }
}
