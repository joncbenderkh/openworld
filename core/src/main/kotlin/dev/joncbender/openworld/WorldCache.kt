package dev.joncbender.openworld

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.math.Vector3
import dev.joncbender.openworld.geo.Face
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Caches a generated [SphereWorld] to disk so a subsequent launch with the
 * same frequency/seed/resource density can load it instead of regenerating
 * it - at the game's current tile count (~396k), generation takes upwards of
 * ten seconds even after several rounds of optimization, almost all of it
 * building sphere topology and hydrology that's wasted work if nothing about
 * the world actually changed since last time.
 *
 * A plain binary format via Data{Input,Output}Stream rather than any generic
 * (de)serialization library - there isn't one in this project's dependency
 * set already, and the shape here (a flat list of fixed-layout records) is
 * about as simple a case as serialization gets.
 */
object WorldCache {
    private const val FORMAT_VERSION = 1

    /**
     * Returns the cached world if [file] holds one matching [frequency],
     * [seed], and [resourceDensity] exactly - or null on any mismatch,
     * missing file, or read failure (a stale format from an older app
     * version included), in which case the caller should regenerate.
     */
    fun load(file: FileHandle, frequency: Int, seed: Long, resourceDensity: Float): SphereWorld? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(file.read())).use { input ->
                if (input.readInt() != FORMAT_VERSION) return null
                if (input.readInt() != frequency) return null
                if (input.readLong() != seed) return null
                if (input.readFloat() != resourceDensity) return null
                readWorld(input)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort: a failed write shouldn't crash or block gameplay, just means the next launch regenerates. */
    fun save(file: FileHandle, world: SphereWorld, frequency: Int, seed: Long, resourceDensity: Float) {
        try {
            DataOutputStream(BufferedOutputStream(file.write(false))).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeInt(frequency)
                output.writeLong(seed)
                output.writeFloat(resourceDensity)
                writeWorld(output, world)
            }
        } catch (e: Exception) {
            // Ignored - see doc comment above.
        }
    }

    private fun writeWorld(output: DataOutputStream, world: SphereWorld) {
        output.writeInt(world.faces.size)
        for (i in world.faces.indices) {
            val face = world.faces[i]
            output.writeFloat(face.center.x)
            output.writeFloat(face.center.y)
            output.writeFloat(face.center.z)
            output.writeByte(face.corners.size)
            for (corner in face.corners) {
                output.writeFloat(corner.x)
                output.writeFloat(corner.y)
                output.writeFloat(corner.z)
            }
            for (neighbor in face.neighbors) output.writeInt(neighbor)

            output.writeByte(world[i].ordinal)
            val resources = world.resourcesAt(i)
            output.writeByte(resources.size)
            for (resource in resources) output.writeByte(resource.ordinal)
        }
    }

    private fun readWorld(input: DataInputStream): SphereWorld {
        val faceCount = input.readInt()
        val faces = ArrayList<Face>(faceCount)
        val biomes = Array(faceCount) { Biome.OCEAN }
        val resourcesPerFace = arrayOfNulls<List<Resource>>(faceCount)

        for (i in 0 until faceCount) {
            val center = Vector3(input.readFloat(), input.readFloat(), input.readFloat())
            val cornerCount = input.readUnsignedByte()
            val corners = ArrayList<Vector3>(cornerCount)
            repeat(cornerCount) {
                corners.add(Vector3(input.readFloat(), input.readFloat(), input.readFloat()))
            }
            val neighbors = IntArray(cornerCount) { input.readInt() }
            faces.add(Face(center, corners, neighbors))

            biomes[i] = Biome.entries[input.readUnsignedByte()]
            val resourceCount = input.readUnsignedByte()
            resourcesPerFace[i] = List(resourceCount) { Resource.entries[input.readUnsignedByte()] }
        }

        val world = SphereWorld(faces, biomes)
        for (i in 0 until faceCount) {
            val resources = resourcesPerFace[i]
            if (!resources.isNullOrEmpty()) world.setResources(i, resources)
        }
        return world
    }
}
