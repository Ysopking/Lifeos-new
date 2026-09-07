package app.lifeos.core.image

import kotlin.math.sqrt

/** Uniformly sampled visible-spectrum grid. */
data class SpectralGrid(
    val startNm: Int = 380,
    val endNm: Int = 780,
    val stepNm: Int = 5,
) {
    init {
        require(startNm in 300..900)
        require(endNm > startNm)
        require(stepNm > 0)
        require((endNm - startNm) % stepNm == 0)
    }

    val bandCount: Int = ((endNm - startNm) / stepNm) + 1
    fun wavelengthNm(index: Int): Int {
        require(index in 0 until bandCount)
        return startNm + index * stepNm
    }
}

data class SolarVector(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }

    fun normalized(): SolarVector {
        val length = sqrt(x * x + y * y + z * z)
        require(length > 0.0)
        return SolarVector(x / length, y / length, z / length)
    }
}

data class SurfaceNormal(val x: Double, val y: Double, val z: Double) {
    fun normalized(): SurfaceNormal {
        val length = sqrt(x * x + y * y + z * z)
        require(length > 0.0)
        return SurfaceNormal(x / length, y / length, z / length)
    }

    fun dot(direction: SolarVector): Double = x * direction.x + y * direction.y + z * direction.z
}

data class RgbSample(val r: Double, val g: Double, val b: Double) {
    init {
        require(r in 0.0..1.0)
        require(g in 0.0..1.0)
        require(b in 0.0..1.0)
    }
}

data class SpectralCurve(
    val grid: SpectralGrid,
    val values: DoubleArray,
) {
    init {
        require(values.size == grid.bandCount)
        require(values.all { it.isFinite() })
    }

    operator fun get(index: Int): Double = values[index]
}

data class SpectralPixel(
    val reflectance: SpectralCurve,
    val normal: SurfaceNormal,
    val confidence: Double,
    val reconstructionError: Double,
) {
    init {
        require(confidence in 0.0..1.0)
        require(reconstructionError.isFinite() && reconstructionError >= 0.0)
        require(reflectance.values.all { it in 0.0..1.0 })
    }
}

data class SpectralImage(
    val width: Int,
    val height: Int,
    val grid: SpectralGrid,
    val reflectance: DoubleArray,
) {
    init {
        require(width > 0 && height > 0)
        require(reflectance.size == width * height * grid.bandCount)
        require(reflectance.all { it.isFinite() && it in 0.0..1.0 })
    }

    fun band(x: Int, y: Int, band: Int): Double {
        require(x in 0 until width && y in 0 until height)
        require(band in 0 until grid.bandCount)
        return reflectance[((y * width + x) * grid.bandCount) + band]
    }
}

data class IlluminantSpectrum(
    val name: String,
    val curve: SpectralCurve,
) {
    init { require(name.isNotBlank()); require(curve.values.all { it >= 0.0 }) }
}

data class SolarPosition(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val zenithDeg: Double,
    val vector: SolarVector,
) {
    init {
        require(azimuthDeg.isFinite() && elevationDeg.isFinite() && zenithDeg.isFinite())
    }
}
