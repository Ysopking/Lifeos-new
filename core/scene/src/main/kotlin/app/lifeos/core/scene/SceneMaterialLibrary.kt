package app.lifeos.core.scene

import kotlin.math.absoluteValue

data class SceneRasterMaterial(
    val linearR: Float,
    val linearG: Float,
    val linearB: Float,
    val roughness: Float,
) {
    init {
        require(linearR in 0f..1f && linearG in 0f..1f && linearB in 0f..1f)
        require(roughness in 0.04f..1f)
    }
}

enum class HumanoidPart { HEAD, TORSO, HIPS, ARM, LEG }

/** Small deterministic material set used by the reference rasterizer only. */
class SceneMaterialLibrary {
    fun material(node: SceneNode, humanoidPart: HumanoidPart? = null): SceneRasterMaterial = when (node.geometry.materialClass) {
        "grass-diffuse" -> SceneRasterMaterial(0.055f, 0.235f, 0.045f, 0.88f)
        "neutral-ground" -> SceneRasterMaterial(0.24f, 0.25f, 0.26f, 0.82f)
        "football-leather" -> SceneRasterMaterial(0.76f, 0.76f, 0.72f, 0.56f)
        "human-generic-layered" -> humanMaterial(node.geometry.seed, humanoidPart ?: HumanoidPart.TORSO)
        "painted-metal-glass" -> SceneRasterMaterial(0.18f, 0.24f, 0.34f, 0.24f)
        "building-generic" -> SceneRasterMaterial(0.42f, 0.38f, 0.33f, 0.72f)
        else -> SceneRasterMaterial(0.42f, 0.42f, 0.42f, 0.75f)
    }

    private fun humanMaterial(seed: Long, part: HumanoidPart): SceneRasterMaterial {
        if (part == HumanoidPart.HEAD) return SceneRasterMaterial(0.48f, 0.27f, 0.18f, 0.62f)
        if (part == HumanoidPart.ARM) return SceneRasterMaterial(0.34f, 0.18f, 0.12f, 0.66f)
        val palette = arrayOf(
            SceneRasterMaterial(0.08f, 0.16f, 0.55f, 0.72f),
            SceneRasterMaterial(0.55f, 0.08f, 0.07f, 0.72f),
            SceneRasterMaterial(0.08f, 0.42f, 0.18f, 0.74f),
            SceneRasterMaterial(0.52f, 0.36f, 0.05f, 0.76f),
        )
        val index = (seed % palette.size).toInt().absoluteValue
        return when (part) {
            HumanoidPart.LEG -> SceneRasterMaterial(0.055f, 0.06f, 0.075f, 0.78f)
            HumanoidPart.HIPS -> palette[index].copy(roughness = 0.76f)
            else -> palette[index]
        }
    }
}
