package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

data class SelfStateWorldClassificationPolicy(
    val version: String,
    val criticalHealthBelow: Double,
    val criticalReadinessBelow: Double,
    val degradedUncertaintyAtLeast: Double,
    val degradedHealthBelow: Double,
    val degradedReadinessBelow: Double,
    val observeUncertaintyAtLeast: Double,
    val observeHealthBelow: Double,
    val observeReadinessBelow: Double,
    val observeContextBelow: Double,
    val observeSalienceAtLeast: Double,
) {
    init {
        require(version.isNotBlank())
        listOf(
            criticalHealthBelow,
            criticalReadinessBelow,
            degradedUncertaintyAtLeast,
            degradedHealthBelow,
            degradedReadinessBelow,
            observeUncertaintyAtLeast,
            observeHealthBelow,
            observeReadinessBelow,
            observeContextBelow,
            observeSalienceAtLeast,
        ).forEach {
            require(it.isFinite() && it in 0.0..1.0) {
                "Self-state classification threshold must be finite and in 0..1"
            }
        }
        require(criticalHealthBelow <= degradedHealthBelow)
        require(degradedHealthBelow <= observeHealthBelow)
        require(criticalReadinessBelow <= degradedReadinessBelow)
        require(degradedReadinessBelow <= observeReadinessBelow)
        require(observeUncertaintyAtLeast <= degradedUncertaintyAtLeast)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "self-state-world-classification-policy/v1",
        version,
        java.lang.Double.toHexString(criticalHealthBelow),
        java.lang.Double.toHexString(criticalReadinessBelow),
        java.lang.Double.toHexString(degradedUncertaintyAtLeast),
        java.lang.Double.toHexString(degradedHealthBelow),
        java.lang.Double.toHexString(degradedReadinessBelow),
        java.lang.Double.toHexString(observeUncertaintyAtLeast),
        java.lang.Double.toHexString(observeHealthBelow),
        java.lang.Double.toHexString(observeReadinessBelow),
        java.lang.Double.toHexString(observeContextBelow),
        java.lang.Double.toHexString(observeSalienceAtLeast),
    )

    companion object {
        val V1 = SelfStateWorldClassificationPolicy(
            version = "self-state-world-classification-v1",
            criticalHealthBelow = 0.35,
            criticalReadinessBelow = 0.25,
            degradedUncertaintyAtLeast = 0.50,
            degradedHealthBelow = 0.65,
            degradedReadinessBelow = 0.50,
            observeUncertaintyAtLeast = 0.20,
            observeHealthBelow = 0.85,
            observeReadinessBelow = 0.75,
            observeContextBelow = 0.50,
            observeSalienceAtLeast = 0.35,
        )
    }
}
