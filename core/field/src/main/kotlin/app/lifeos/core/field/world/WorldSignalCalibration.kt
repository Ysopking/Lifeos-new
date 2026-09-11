package app.lifeos.core.field.world

import app.lifeos.core.field.StableFieldIds

enum class WorldSignalSaturation {
    CLAMP_0_1,
    RATIONAL_POSITIVE,
}

data class WorldSignalCalibrationRule(
    val dimension: WorldSignalDimension,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val saturation: WorldSignalSaturation = WorldSignalSaturation.CLAMP_0_1,
) {
    init {
        require(scale.isFinite() && scale >= 0.0) { "World signal calibration scale must be finite and non-negative" }
        require(offset.isFinite()) { "World signal calibration offset must be finite" }
    }

    fun normalize(raw: Double): Double {
        require(raw.isFinite()) { "World signal input must be finite" }
        val adjusted = raw * scale + offset
        return when (saturation) {
            WorldSignalSaturation.CLAMP_0_1 -> adjusted.coerceIn(0.0, 1.0)
            WorldSignalSaturation.RATIONAL_POSITIVE -> {
                val positive = adjusted.coerceAtLeast(0.0)
                (positive / (1.0 + positive)).coerceIn(0.0, 1.0)
            }
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-signal-calibration-rule/v1",
        dimension.name,
        java.lang.Double.toHexString(scale),
        java.lang.Double.toHexString(offset),
        saturation.name,
    )
}

data class WorldSignalCalibrationProfile(
    val version: String,
    val rules: List<WorldSignalCalibrationRule>,
) {
    init {
        require(version.isNotBlank()) { "World signal calibration version must not be blank" }
        require(rules.map { it.dimension }.distinct().size == rules.size) {
            "World signal calibration dimensions must be unique"
        }
        require(rules.map { it.dimension }.toSet() == WorldSignalDimension.entries.toSet()) {
            "World signal calibration must explicitly define every world dimension"
        }
    }

    private val byDimension = rules.associateBy { it.dimension }

    fun rule(dimension: WorldSignalDimension): WorldSignalCalibrationRule =
        byDimension.getValue(dimension)

    fun normalize(dimension: WorldSignalDimension, raw: Double): Double =
        rule(dimension).normalize(raw)

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-signal-calibration-profile/v1",
        version,
        *rules.sortedBy { it.dimension.name }.map { it.fingerprint() }.toTypedArray(),
    )

    companion object {
        val V1 = WorldSignalCalibrationProfile(
            version = "world-signals-v1",
            rules = WorldSignalDimension.entries.map { dimension ->
                WorldSignalCalibrationRule(
                    dimension = dimension,
                    saturation = if (dimension == WorldSignalDimension.GOAL_RELEVANCE) {
                        WorldSignalSaturation.RATIONAL_POSITIVE
                    } else {
                        WorldSignalSaturation.CLAMP_0_1
                    },
                )
            },
        )
    }
}

class WorldSignalCalibrator(
    val profile: WorldSignalCalibrationProfile = WorldSignalCalibrationProfile.V1,
) {
    fun value(
        dimension: WorldSignalDimension,
        raw: Double,
        confidence: Double,
        provenanceFingerprints: Set<String>,
    ): WorldDimensionValue = WorldDimensionValue(
        dimension = dimension,
        value = profile.normalize(dimension, raw),
        confidence = confidence.coerceIn(0.0, 1.0),
        provenanceFingerprints = provenanceFingerprints,
    )
}
