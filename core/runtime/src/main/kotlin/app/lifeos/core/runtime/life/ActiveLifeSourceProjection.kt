package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds
import java.nio.charset.StandardCharsets
import java.time.Instant
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
        val retiredSources = DurableLifeSourceRetirement.retiredSources(allPhotons)
        if (activeAdapters.isEmpty() && retiredSources.isEmpty()) return authoritative
        return authoritative.filter { photon ->
            val sourceRecord =
                "life-source-evidence" in photon.tags ||
                    "life-source-gap" in photon.tags
            if (!sourceRecord) return@filter true
            val sourceId = photon.singleTagValue(SOURCE_PREFIX) ?: return@filter true
            if (sourceId in retiredSources) return@filter false
            if ("life-source-evidence" !in photon.tags) return@filter true
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

/**
 * Durable management marker for moving one source out of the legacy LifeSource pipeline.
 *
 * Evidence remains immutable in the Photon store for provenance/recovery, but Graph/Memory stop
 * projecting it once the replacement authority has durably taken ownership.
 */
internal object DurableLifeSourceRetirement {
    data class PersistResult(
        val marker: Photon,
        val created: Boolean,
    )

    suspend fun persist(
        photons: PhotonRepository,
        sourceId: String,
        replacementAuthority: String,
        retiredAt: Instant,
    ): PersistResult {
        require(sourceId.isNotBlank())
        require(replacementAuthority.isNotBlank())
        val id = markerId(sourceId, replacementAuthority)
        val existing = photons.load(id)
        if (existing != null) {
            validate(existing, sourceId, replacementAuthority)
            return PersistResult(existing, created = false)
        }

        val marker = Photon(
            id = id,
            content = buildString {
                appendLine("schema=" + RETIREMENT_SCHEMA)
                appendLine("source=" + encodeRetirement(sourceId))
                append("replacement=" + encodeRetirement(replacementAuthority))
            },
            mimeType = RETIREMENT_MIME,
            semanticMass = 0.0,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "life-source-retirement",
                actor = replacementAuthority,
                createdAt = retiredAt,
            ),
            tags = setOf(
                "life-memory-management",
                "life-source-retirement",
                "source:$sourceId",
                "replacement-authority:$replacementAuthority",
            ),
        )
        photons.save(marker)
        check(photons.load(id) == marker) {
            "Life-source retirement marker was not durable after save"
        }
        return PersistResult(marker, created = true)
    }

    fun retiredSources(allPhotons: List<Photon>): Set<String> =
        allPhotons.mapNotNull(::retiredSource).toSortedSet()

    private fun retiredSource(photon: Photon): String? = runCatching {
        if ("life-source-retirement" !in photon.tags) return@runCatching null
        require(photon.mimeType == RETIREMENT_MIME)
        val sourceTag = photon.tags
            .filter { it.startsWith(SOURCE_PREFIX) }
            .single()
            .removePrefix(SOURCE_PREFIX)
        require(sourceTag.isNotBlank())
        val fields = retirementFields(photon.content)
        require(fields["schema"] == RETIREMENT_SCHEMA)
        require(decodeRetirement(requireNotNull(fields["source"])) == sourceTag)
        require(decodeRetirement(requireNotNull(fields["replacement"])).isNotBlank())
        sourceTag
    }.getOrNull()

    private fun validate(
        photon: Photon,
        sourceId: String,
        replacementAuthority: String,
    ) {
        require(retiredSource(photon) == sourceId) {
            "Life-source retirement marker identity mismatch"
        }
        val fields = retirementFields(photon.content)
        require(
            decodeRetirement(requireNotNull(fields["replacement"])) ==
                replacementAuthority
        ) {
            "Life-source retirement replacement authority mismatch"
        }
    }

    private fun markerId(
        sourceId: String,
        replacementAuthority: String,
    ): PhotonId = PhotonId(
        "life-source-retirement-" +
            StableCognitiveIds.fingerprint(
                "life-source-retirement-id/v1",
                sourceId,
                replacementAuthority,
            )
    )

    private fun retirementFields(content: String): Map<String, String> {
        require(content.length <= 16 * 1024)
        val fields = linkedMapOf<String, String>()
        content.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator > 0)
                require(
                    fields.put(
                        line.substring(0, separator),
                        line.substring(separator + 1),
                    ) == null
                )
            }
        return fields
    }

    private fun encodeRetirement(value: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                value.toByteArray(StandardCharsets.UTF_8)
            )

    private fun decodeRetirement(value: String): String =
        String(
            Base64.getUrlDecoder().decode(value),
            StandardCharsets.UTF_8,
        )

    private const val SOURCE_PREFIX = "source:"
    private const val RETIREMENT_SCHEMA = "1"
    private const val RETIREMENT_MIME =
        "application/vnd.lifeos.life-source-retirement+text"
}
