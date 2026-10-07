package dev.joncbender.openworld

import dev.joncbender.openworld.geo.GeodesicSphere
import dev.joncbender.openworld.geo.Sphere
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Groups a sphere's faces into compact patches, so each patch has a tight bounding cap.
 *
 * Face indices run in rows across each of the icosahedron's 20 triangles, so a run of
 * consecutive faces is a thin strip as wide as a whole triangle (~63 degrees): its bounding
 * cap is huge and almost every strip is "in view" from almost anywhere, which defeats
 * culling. Here each face goes to the cell of a small grid laid over the triangle it sits
 * in (found by projecting its center onto that triangle), giving roughly square patches a
 * few degrees across.
 *
 * Only non-empty cells become patches, numbered in cell order, and a patch lists its faces
 * in ascending index order, so the layout is deterministic. The grid is made finer until no
 * patch exceeds [maxFacesPerPatch].
 */
class PatchLayout private constructor(
    val patchCount: Int,
    private val patchStart: IntArray,
    /** Every face, grouped by patch: patch p's faces are `faceIndices[start(p) until end(p)]`. */
    val faceIndices: IntArray,
) {
    fun start(patch: Int): Int = patchStart[patch]
    fun end(patch: Int): Int = patchStart[patch + 1]
    fun faceCount(patch: Int): Int = patchStart[patch + 1] - patchStart[patch]

    companion object {
        // Aims for roughly this many faces in a full cell: small enough for a tight cap and
        // a cheap mesh build, large enough that draw calls stay in the hundreds.
        private const val TARGET_FACES_PER_CELL = 2500

        fun build(sphere: Sphere, maxFacesPerPatch: Int): PatchLayout {
            var cellsPerEdge = maxOf(1, ceil(sqrt(sphere.faceCount / (TARGET_FACES_PER_CELL * 10.0))).toInt())
            while (true) {
                tryBuild(sphere, cellsPerEdge, maxFacesPerPatch)?.let { return it }
                cellsPerEdge++
            }
        }

        private fun tryBuild(sphere: Sphere, g: Int, maxFacesPerPatch: Int): PatchLayout? {
            val faceCount = sphere.faceCount
            val cellOf = IntArray(faceCount)
            Parallel.forEachIndex(faceCount) { i ->
                cellOf[i] = Triangles.cellOf(sphere.centerX(i), sphere.centerY(i), sphere.centerZ(i), g)
            }

            val cellCount = 20 * g * g
            val counts = IntArray(cellCount)
            for (cell in cellOf) counts[cell]++
            if (counts.max() > maxFacesPerPatch) return null

            // Number the non-empty cells as patches and lay their faces out back to back.
            val patchOfCell = IntArray(cellCount) { -1 }
            var patchCount = 0
            for (cell in 0 until cellCount) if (counts[cell] > 0) patchOfCell[cell] = patchCount++
            val patchStart = IntArray(patchCount + 1)
            for (cell in 0 until cellCount) if (counts[cell] > 0) patchStart[patchOfCell[cell] + 1] = counts[cell]
            for (p in 0 until patchCount) patchStart[p + 1] += patchStart[p]

            val cursor = patchStart.copyOf(patchCount)
            val faceIndices = IntArray(faceCount)
            for (face in 0 until faceCount) faceIndices[cursor[patchOfCell[cellOf[face]]]++] = face
            return PatchLayout(patchCount, patchStart, faceIndices)
        }
    }

    /** The 20 base triangles, precomputed for projecting a point onto the nearest one. */
    private object Triangles {
        private val count = GeodesicSphere.BASE_FACES.size
        private val centroid = FloatArray(count * 3)
        private val origin = FloatArray(count * 3)   // vertex a
        private val edge1 = FloatArray(count * 3)    // b - a
        private val edge2 = FloatArray(count * 3)    // c - a
        private val normal = FloatArray(count * 3)   // edge1 x edge2
        private val normalSquared = FloatArray(count)
        private val normalDotOrigin = FloatArray(count)

        init {
            for ((t, face) in GeodesicSphere.BASE_FACES.withIndex()) {
                val a = GeodesicSphere.BASE_VERTICES[face[0]]
                val b = GeodesicSphere.BASE_VERTICES[face[1]]
                val c = GeodesicSphere.BASE_VERTICES[face[2]]
                val sx = a.x + b.x + c.x
                val sy = a.y + b.y + c.y
                val sz = a.z + b.z + c.z
                val length = sqrt(sx * sx + sy * sy + sz * sz)
                val e1x = b.x - a.x; val e1y = b.y - a.y; val e1z = b.z - a.z
                val e2x = c.x - a.x; val e2y = c.y - a.y; val e2z = c.z - a.z
                val nx = e1y * e2z - e1z * e2y
                val ny = e1z * e2x - e1x * e2z
                val nz = e1x * e2y - e1y * e2x
                centroid[t * 3] = sx / length; centroid[t * 3 + 1] = sy / length; centroid[t * 3 + 2] = sz / length
                origin[t * 3] = a.x; origin[t * 3 + 1] = a.y; origin[t * 3 + 2] = a.z
                edge1[t * 3] = e1x; edge1[t * 3 + 1] = e1y; edge1[t * 3 + 2] = e1z
                edge2[t * 3] = e2x; edge2[t * 3 + 1] = e2y; edge2[t * 3 + 2] = e2z
                normal[t * 3] = nx; normal[t * 3 + 1] = ny; normal[t * 3 + 2] = nz
                normalSquared[t] = nx * nx + ny * ny + nz * nz
                normalDotOrigin[t] = nx * a.x + ny * a.y + nz * a.z
            }
        }

        /** Which cell of the g-by-g grid over the nearest base triangle the unit vector (px, py, pz) falls in. */
        fun cellOf(px: Float, py: Float, pz: Float, g: Int): Int {
            var t = 0
            var best = -2f
            for (k in 0 until count) {
                val d = centroid[k * 3] * px + centroid[k * 3 + 1] * py + centroid[k * 3 + 2] * pz
                if (d > best) { best = d; t = k }
            }
            // Project the point from the center of the sphere onto the triangle's plane, then take
            // barycentric coordinates there (weights of the b and c corners).
            val nx = normal[t * 3]; val ny = normal[t * 3 + 1]; val nz = normal[t * 3 + 2]
            val scale = normalDotOrigin[t] / (nx * px + ny * py + nz * pz)
            val qx = px * scale - origin[t * 3]
            val qy = py * scale - origin[t * 3 + 1]
            val qz = pz * scale - origin[t * 3 + 2]
            val e1x = edge1[t * 3]; val e1y = edge1[t * 3 + 1]; val e1z = edge1[t * 3 + 2]
            val e2x = edge2[t * 3]; val e2y = edge2[t * 3 + 1]; val e2z = edge2[t * 3 + 2]
            val wb = ((qy * e2z - qz * e2y) * nx + (qz * e2x - qx * e2z) * ny + (qx * e2y - qy * e2x) * nz) / normalSquared[t]
            val wc = ((e1y * qz - e1z * qy) * nx + (e1z * qx - e1x * qz) * ny + (e1x * qy - e1y * qx) * nz) / normalSquared[t]
            val ia = floor(wb * g).toInt().coerceIn(0, g - 1)
            val ib = floor(wc * g).toInt().coerceIn(0, g - 1)
            return (t * g + ia) * g + ib
        }
    }
}
