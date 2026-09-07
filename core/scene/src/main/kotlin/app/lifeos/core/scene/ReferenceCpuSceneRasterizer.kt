package app.lifeos.core.scene

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic primary-ray reference rasterizer for canonical LIFEOS scene primitives.
 * It performs geometry/material projection only; lighting belongs to MMSI forward synthesis.
 */
class ReferenceCpuSceneRasterizer(
    private val materials: SceneMaterialLibrary = SceneMaterialLibrary(),
    private val nearMeters: Double = 0.10,
    private val farMeters: Double = 50.0,
) : SceneRasterizer {
    init {
        require(nearMeters > 0.0)
        require(farMeters > nearMeters)
    }

    override fun rasterize(graph: ProceduralSceneGraph, size: SceneRasterSize): SceneRasterResult {
        val unsupported = graph.nodes.filterNot { it.geometry.primitive in SUPPORTED_PRIMITIVES }
        if (unsupported.isNotEmpty()) {
            return SceneRasterResult.Blocked(
                unsupported.map { "unsupported primitive:${it.geometry.primitive}:${it.id.value}" }
            )
        }
        val rotated = graph.nodes.filter { node ->
            val r = node.transform.rotationDeg
            abs(r.x) > ROTATION_EPSILON || abs(r.y) > ROTATION_EPSILON || abs(r.z) > ROTATION_EPSILON
        }
        if (rotated.isNotEmpty()) {
            return SceneRasterResult.Blocked(rotated.map { "rotation not supported by reference rasterizer:${it.id.value}" })
        }
        val invalidScale = graph.nodes.filter { node ->
            val s = node.transform.scale
            s.x <= 0.0 || s.y <= 0.0 || s.z <= 0.0
        }
        if (invalidScale.isNotEmpty()) {
            return SceneRasterResult.Blocked(invalidScale.map { "non-positive scale:${it.id.value}" })
        }

        val camera = CameraFrame.from(graph.camera, size)
        val pixels = size.pixelCount
        val albedo = FloatArray(pixels * 4)
        val normals = FloatArray(pixels * 3)
        val depth = FloatArray(pixels) { 1f }
        val roughness = FloatArray(pixels) { 1f }

        // Background carries a stable alpha and forward-facing normal, while depth=1 marks no coverage.
        for (i in 0 until pixels) {
            albedo[i * 4 + 3] = 1f
            normals[i * 3 + 2] = 1f
        }

        var covered = 0
        var actors = 0
        var objects = 0
        var ground = 0

        for (y in 0 until size.height) {
            for (x in 0 until size.width) {
                val pixel = y * size.width + x
                val ray = camera.rayForPixel(x, y, size)
                val hit = nearestHit(ray, graph.nodes) ?: continue
                if (hit.distance !in nearMeters..farMeters) continue

                val material = materials.material(hit.node, hit.humanoidPart)
                val a = pixel * 4
                albedo[a] = material.linearR
                albedo[a + 1] = material.linearG
                albedo[a + 2] = material.linearB
                albedo[a + 3] = 1f

                val viewNormal = camera.worldNormalToView(hit.normal)
                val n = pixel * 3
                normals[n] = viewNormal.x.toFloat()
                normals[n + 1] = viewNormal.y.toFloat()
                normals[n + 2] = viewNormal.z.toFloat()
                depth[pixel] = normalizeDepth(hit.distance).toFloat()
                roughness[pixel] = material.roughness

                covered++
                when (hit.node.role) {
                    SceneNodeRole.ACTOR -> actors++
                    SceneNodeRole.OBJECT -> objects++
                    SceneNodeRole.GROUND -> ground++
                    SceneNodeRole.ENVIRONMENT -> Unit
                }
            }
        }

        return SceneRasterResult.Rasterized(
            buffers = MmsiSceneRasterBuffers(
                size = size,
                albedoLinearRgba = albedo,
                normalsXyz = normals,
                depth = depth,
                roughness = roughness,
            ),
            stats = SceneRasterStats(
                coveredPixels = covered,
                actorPixels = actors,
                objectPixels = objects,
                groundPixels = ground,
            ),
        )
    }

    private fun normalizeDepth(distance: Double): Double =
        ((distance - nearMeters) / (farMeters - nearMeters)).coerceIn(0.0, 1.0)

    private fun nearestHit(ray: Ray, nodes: List<SceneNode>): Hit? {
        var nearest: Hit? = null
        for (node in nodes) {
            val hit = when (node.geometry.primitive) {
                GeometryPrimitive.SPHERE -> intersectSphereNode(ray, node)
                GeometryPrimitive.PLANE -> intersectPlaneNode(ray, node)
                GeometryPrimitive.BOX -> intersectBoxNode(ray, node)
                GeometryPrimitive.PARAMETRIC_HUMANOID -> intersectHumanoid(ray, node)
            } ?: continue
            if (hit.distance > EPSILON && (nearest == null || hit.distance < nearest.distance)) nearest = hit
        }
        return nearest
    }

    private fun intersectSphereNode(ray: Ray, node: SceneNode): Hit? {
        val dimensions = scaledDimensions(node)
        val radii = Vec3(
            dimensions.x * 0.5,
            dimensions.y * 0.5,
            dimensions.z * 0.5,
        )
        return intersectEllipsoid(ray, Vec3.from(node.transform.position), radii)?.let { intersection ->
            Hit(node, intersection.distance, intersection.normal)
        }
    }

    private fun intersectPlaneNode(ray: Ray, node: SceneNode): Hit? {
        val dimensions = scaledDimensions(node)
        if (abs(ray.direction.z) < EPSILON) return null
        val center = Vec3.from(node.transform.position)
        val surfaceZ = center.z + dimensions.z * 0.5
        val t = (surfaceZ - ray.origin.z) / ray.direction.z
        if (t <= EPSILON) return null
        val p = ray.at(t)
        if (abs(p.x - center.x) > dimensions.x * 0.5 || abs(p.y - center.y) > dimensions.y * 0.5) return null
        return Hit(node, t, Vec3(0.0, 0.0, 1.0))
    }

    private fun intersectBoxNode(ray: Ray, node: SceneNode): Hit? {
        val dimensions = scaledDimensions(node)
        val center = Vec3.from(node.transform.position)
        val half = dimensions * 0.5
        val minCorner = center - half
        val maxCorner = center + half
        var tMin = Double.NEGATIVE_INFINITY
        var tMax = Double.POSITIVE_INFINITY

        for (axis in 0..2) {
            val origin = ray.origin.component(axis)
            val direction = ray.direction.component(axis)
            val low = minCorner.component(axis)
            val high = maxCorner.component(axis)
            if (abs(direction) < EPSILON) {
                if (origin < low || origin > high) return null
                continue
            }
            var t1 = (low - origin) / direction
            var t2 = (high - origin) / direction
            if (t1 > t2) {
                val temp = t1
                t1 = t2
                t2 = temp
            }
            tMin = max(tMin, t1)
            tMax = min(tMax, t2)
            if (tMin > tMax) return null
        }
        val t = if (tMin > EPSILON) tMin else tMax
        if (t <= EPSILON) return null
        val p = ray.at(t)
        val local = p - center
        val normalizedFaceDistance = listOf(
            abs(abs(local.x) - half.x) to Vec3(if (local.x >= 0.0) 1.0 else -1.0, 0.0, 0.0),
            abs(abs(local.y) - half.y) to Vec3(0.0, if (local.y >= 0.0) 1.0 else -1.0, 0.0),
            abs(abs(local.z) - half.z) to Vec3(0.0, 0.0, if (local.z >= 0.0) 1.0 else -1.0),
        )
        val normal = normalizedFaceDistance.minBy { it.first }.second
        return Hit(node, t, normal)
    }

    private fun intersectHumanoid(ray: Ray, node: SceneNode): Hit? {
        val dimensions = scaledDimensions(node)
        val center = Vec3.from(node.transform.position)
        val width = dimensions.x
        val depth = dimensions.y
        val height = dimensions.z
        val parts = humanoidParts(node, center, width, depth, height)
        var nearest: Hit? = null
        for (part in parts) {
            val intersection = intersectEllipsoid(ray, part.center, part.radii) ?: continue
            val hit = Hit(node, intersection.distance, intersection.normal, part.part)
            if (nearest == null || hit.distance < nearest.distance) nearest = hit
        }
        return nearest
    }

    private fun humanoidParts(
        node: SceneNode,
        center: Vec3,
        width: Double,
        depth: Double,
        height: Double,
    ): List<HumanoidEllipsoid> {
        val parts = mutableListOf(
            HumanoidEllipsoid(
                HumanoidPart.HEAD,
                center + Vec3(0.0, 0.0, height * 0.40),
                Vec3(width * 0.24, depth * 0.39, height * 0.10),
            ),
            HumanoidEllipsoid(
                HumanoidPart.TORSO,
                center + Vec3(0.0, 0.0, height * 0.08),
                Vec3(width * 0.38, depth * 0.45, height * 0.23),
            ),
            HumanoidEllipsoid(
                HumanoidPart.HIPS,
                center + Vec3(0.0, 0.0, -height * 0.18),
                Vec3(width * 0.34, depth * 0.44, height * 0.12),
            ),
        )

        val receivePose = node.geometry.poseId == "football-receive-ready-v1"
        val kickPose = node.geometry.poseId == "football-kick-prep-v1"
        val armX = if (receivePose) width * 0.64 else width * 0.52
        val armY = if (receivePose) depth * 0.20 else 0.0
        for (side in listOf(-1.0, 1.0)) {
            parts += HumanoidEllipsoid(
                HumanoidPart.ARM,
                center + Vec3(side * armX, armY, height * 0.07),
                Vec3(width * 0.16, depth * 0.30, height * 0.20),
            )
        }

        for (side in listOf(-1.0, 1.0)) {
            val kickingLeg = kickPose && side > 0.0
            parts += HumanoidEllipsoid(
                HumanoidPart.LEG,
                center + Vec3(
                    side * width * 0.22,
                    if (kickingLeg) depth * 0.75 else 0.0,
                    if (kickingLeg) -height * 0.27 else -height * 0.36,
                ),
                Vec3(
                    width * 0.16,
                    if (kickingLeg) depth * 0.52 else depth * 0.32,
                    if (kickingLeg) height * 0.18 else height * 0.24,
                ),
            )
        }
        return parts
    }

    private fun scaledDimensions(node: SceneNode): Vec3 {
        val d = node.geometry.dimensionsMeters
        val s = node.transform.scale
        return Vec3(d.x * s.x, d.y * s.y, d.z * s.z)
    }

    private fun intersectEllipsoid(ray: Ray, center: Vec3, radii: Vec3): EllipsoidIntersection? {
        val origin = ray.origin - center
        val scaledOrigin = Vec3(origin.x / radii.x, origin.y / radii.y, origin.z / radii.z)
        val scaledDirection = Vec3(
            ray.direction.x / radii.x,
            ray.direction.y / radii.y,
            ray.direction.z / radii.z,
        )
        val a = scaledDirection dot scaledDirection
        val b = 2.0 * (scaledOrigin dot scaledDirection)
        val c = (scaledOrigin dot scaledOrigin) - 1.0
        val discriminant = b * b - 4.0 * a * c
        if (discriminant < 0.0) return null
        val root = sqrt(discriminant)
        val t0 = (-b - root) / (2.0 * a)
        val t1 = (-b + root) / (2.0 * a)
        val t = when {
            t0 > EPSILON -> t0
            t1 > EPSILON -> t1
            else -> return null
        }
        val p = ray.at(t)
        val local = p - center
        val normal = Vec3(
            local.x / (radii.x * radii.x),
            local.y / (radii.y * radii.y),
            local.z / (radii.z * radii.z),
        ).normalized()
        return EllipsoidIntersection(t, normal)
    }

    private data class HumanoidEllipsoid(
        val part: HumanoidPart,
        val center: Vec3,
        val radii: Vec3,
    )

    private data class EllipsoidIntersection(
        val distance: Double,
        val normal: Vec3,
    )

    private data class Hit(
        val node: SceneNode,
        val distance: Double,
        val normal: Vec3,
        val humanoidPart: HumanoidPart? = null,
    )

    private data class Ray(val origin: Vec3, val direction: Vec3) {
        fun at(distance: Double): Vec3 = origin + direction * distance
    }

    private data class CameraFrame(
        val origin: Vec3,
        val forward: Vec3,
        val right: Vec3,
        val up: Vec3,
        val tanHalfVerticalFov: Double,
    ) {
        fun rayForPixel(x: Int, y: Int, size: SceneRasterSize): Ray {
            val ndcX = ((x + 0.5) / size.width.toDouble()) * 2.0 - 1.0
            val ndcY = 1.0 - ((y + 0.5) / size.height.toDouble()) * 2.0
            val aspect = size.width.toDouble() / size.height.toDouble()
            val direction = (
                forward +
                    right * (ndcX * tanHalfVerticalFov * aspect) +
                    up * (ndcY * tanHalfVerticalFov)
                ).normalized()
            return Ray(origin, direction)
        }

        /** View-space normal where +Z points from the surface toward the camera, matching MMSI's view vector. */
        fun worldNormalToView(normal: Vec3): Vec3 = Vec3(
            normal dot right,
            normal dot up,
            normal dot (forward * -1.0),
        ).normalized()

        companion object {
            fun from(camera: SceneCamera, size: SceneRasterSize): CameraFrame {
                require(size.width > 0 && size.height > 0)
                val origin = Vec3.from(camera.position)
                val target = Vec3.from(camera.target)
                val forward = (target - origin).normalized()
                val worldUp = Vec3(0.0, 0.0, 1.0)
                var right = forward.cross(worldUp)
                if (right.lengthSquared() < EPSILON) right = Vec3(1.0, 0.0, 0.0)
                right = right.normalized()
                val up = right.cross(forward).normalized()
                val tanHalfVerticalFov = (SENSOR_HEIGHT_MM * 0.5) / camera.focalLengthMm
                return CameraFrame(origin, forward, right, up, tanHalfVerticalFov)
            }
        }
    }

    private data class Vec3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
        operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
        infix fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z
        fun cross(other: Vec3) = Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )
        fun component(axis: Int): Double = when (axis) {
            0 -> x
            1 -> y
            2 -> z
            else -> error("axis")
        }
        fun lengthSquared(): Double = this dot this
        fun normalized(): Vec3 {
            val length = sqrt(lengthSquared())
            require(length > EPSILON)
            return this * (1.0 / length)
        }

        companion object {
            fun from(value: SceneVec3) = Vec3(value.x, value.y, value.z)
        }
    }

    private companion object {
        const val EPSILON = 1e-8
        const val ROTATION_EPSILON = 1e-6
        const val SENSOR_HEIGHT_MM = 24.0
        val SUPPORTED_PRIMITIVES = GeometryPrimitive.entries.toSet()
    }
}
