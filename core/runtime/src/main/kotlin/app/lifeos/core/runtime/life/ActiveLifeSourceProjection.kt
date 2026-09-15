package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Projection-only source-adapter supersession.
 *
 * Source evidence remains immutable and retained in the authoritative Photon store. When one source
 * has evidence from multiple adapter generations, Graph/Memory consume exactly one generation:
 *
 * 1. If at least one generation has a completed durable checkpoint, the most recently completed
 *    generation stays active. A newer partial migration therefore cannot replace a complete source.
 * 2. If no generation is complete yet, the most recently advanced checkpoint is active so partial
 *    generations are still not mixed.
 * 3. Sources without valid checkpoints keep their evidence unchanged for backward compatibility.
 *
 * Malformed checkpoints fail open for projection selection: they never hide otherwise authoritative
 * evidence. The checkpoint store itself remains strict when that concrete descriptor is loaded.
 */
internal object ActiveLifeSourceProjection {
    fun filter(
        authoritative: List<Photon>,
        allPhotons: List<Photon>,
    ): List<Photon> {
        val activeAdapters = activeAdapters(allPhotons)
        if (activeAdapters.isEmpty()) return authoritative
        return authoritative.filter { photon ->
            if ("life-source-evidence" !in photon.tags) return@filter true
            val sourceId = photon.singleTagValue(SOURCE_PREFIX) ?: return@filter true
            val adapterVersion = photon.singleTagValue(ADAPTER_PREFIX) ?: return@filter true
            activeAdapters[sourceId]?.let { active -> adapterVersion == active } ?: true
        }
    }

    internal fun activeAdapters(allPhotons: List<Photon>): Map<String, String> = allPhotons
        .mapNotNull(::checkpoint)
        .groupBy { it.sourceId }
        .mapValues { (_, checkpoints) -> selectActive(checkpoints).adapterVersion }
        .toSortedMap()

    private fun selectActive(checkpoints: List<Checkpoint>): Checkpoint {
        val completed = checkpoints.filter { it.complete }
        val candidates = completed.ifEmpty { checkpoints }
        return candidates.maxWith(
            compareBy<Checkpoint> { it.committedAt }
                .thenBy { it.revision }
                .thenBy { it.adapterVersion }
        )
    }

    private fun checkpoint(photon: Photon): Checkpoint? = runCatching {
        if ("life-source-checkpoint" !in photon.tags) return@runCatching null
        val sourceId = requireNotNull(photon.singleTagValue(SOURCE_PREFIX))
        val adapterVersion = requireNotNull(photon.singleTagValue(ADAPTER_PREFIX))
        val fields = parseFields(photon.content)
        require(fields["schema"] == CHECKPOINT_SCHEMA)
        require(decode(required(fields, "source")) == sourceId)
        require(decode(required(fields, "adapter")) == adapterVersion)
        val position = required(fields, "position")
        val batch = required(fields, "batch")
        Checkpoint(
            sourceId = sourceId,
            adapterVersion = adapterVersion,
            complete = position == NULL && batch != NULL && batch.isNotBlank(),
            committedAt = photon.provenance.createdAt,
            revision = photon.revision,
        )
    }.getOrNull()

    private fun Photon.singleTagValue(prefix: String): String? {
        val matches = tags.filter { it.startsWith(prefix) }
        if (matches.size != 1) return null
        return matches.single().removePrefix(prefix).takeIf { it.isNotBlank() }
    }

    private fun parseFields(content: String): Map<String, String> {
        require(content.length <= MAX_CONTENT_CHARS)
        val fields = linkedMapOf<String, String>()
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.size <= MAX_FIELDS)
        lines.forEach { line ->
            require(line.length <= MAX_FIELD_CHARS)
            val separator = line.indexOf('=')
            require(separator > 0)
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(fields.put(key, value) == null)
        }
        return fields
    }

    private fun required(fields: Map<String, String>, key: String): String =
        fields[key] ?: throw IllegalArgumentException("Missing checkpoint field: $key")

    private fun decode(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )

    private data class Checkpoint(
        val sourceId: String,
        val adapterVersion: String,
        val complete: Boolean,
        val committedAt: java.time.Instant,
        val revision: Long,
    )

    private const val SOURCE_PREFIX = "source:"
    private const val ADAPTER_PREFIX = "source-adapter:"
    private const val CHECKPOINT_SCHEMA = "1"
    private const val NULL = "~"
    private const val MAX_CONTENT_CHARS = 64 * 1024
    private const val MAX_FIELDS = 32
    private const val MAX_FIELD_CHARS = 16 * 1024
}
