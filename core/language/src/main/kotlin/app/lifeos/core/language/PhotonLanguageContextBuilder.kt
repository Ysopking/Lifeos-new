package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Instant
import java.util.Locale

class PhotonLanguageContextBuilder {
    private val termRegex = Regex("[\\p{L}\\p{N}]+")

    fun build(
        photons: List<Photon>,
        now: Instant = Instant.now(),
        excludeIds: Set<app.lifeos.core.model.PhotonId> = emptySet(),
    ): LanguageContext {
        val eligible = photons.filterNot { photon ->
            photon.id in excludeIds || "context-record" in photon.tags
        }
        val activeGoal = eligible
            .asSequence()
            .filter {
                "goal" in it.tags &&
                    it.phase != PhotonPhase.ARCHIVED &&
                    "intent:continue" !in it.tags &&
                    "goal-resumed" !in it.tags
            }
            .maxByOrNull { it.provenance.createdAt }
            ?.id
        val items = eligible.map { photon ->
            LanguageContextItem(
                photonId = photon.id,
                kind = inferKind(photon),
                tags = photon.tags,
                createdAt = photon.provenance.createdAt,
                active = photon.id == activeGoal || photon.phase in setOf(PhotonPhase.ACTIVE, PhotonPhase.REFLECTING),
                contentTerms = extractTerms(photon.content),
                confidence = photon.confidence,
                revisionRef = PhotonRevisionRef(photon.id, photon.revision),
                semanticTypes = semanticTypes(photon),
                normalizedTerms = extractTerms(photon.content),
                conceptIds = photon.tags
                    .filter { it.startsWith("concept:") }
                    .mapTo(linkedSetOf()) { it.substringAfter(':') },
                relationKeys = photon.relations.mapTo(linkedSetOf()) {
                    it.type.name + ":" + it.target.value
                },
                conversationId = photon.tags
                    .firstOrNull { it.startsWith("conversation:") }
                    ?.substringAfter(':'),
                matterId = photon.tags
                    .firstOrNull { it.startsWith("matter:") || it.startsWith("life-matter:") }
                    ?.substringAfter(':'),
                goalId = photon.tags
                    .firstOrNull { it.startsWith("goal-id:") }
                    ?.substringAfter(':')
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::PhotonId),
            )
        }
        return LanguageContext(items = items, activeGoalId = activeGoal, now = now)
    }

    private fun semanticTypes(photon: Photon): Set<String> = buildSet {
        add(inferKind(photon))
        photon.tags.forEach { tag ->
            when {
                tag.startsWith("entity:") -> add(tag)
                tag.startsWith("domain:") -> add(tag)
                tag.startsWith("semantic:") -> add(tag)
                tag in setOf("image", "result", "goal", "memory", "life-matter", "legal", "debt") ->
                    add(tag)
            }
        }
    }

    private fun inferKind(photon: Photon): String = when {
        "goal" in photon.tags || photon.mimeType == "application/vnd.lifeos.goal+text" -> "goal"
        "image" in photon.tags || photon.mimeType.startsWith("image/") -> "image"
        "apk" in photon.tags || photon.mimeType == "application/vnd.android.package-archive" -> "apk"
        "module" in photon.tags || photon.tags.any { it.startsWith("module:") } -> "module"
        "file" in photon.tags -> "file"
        "result" in photon.tags -> "result"
        else -> "text"
    }

    private fun extractTerms(content: String): Set<String> = termRegex.findAll(content)
        .map { it.value.lowercase(Locale.ROOT).replace("ß", "ss") }
        .filter { it.length > 1 }
        .take(128)
        .toSet()
}
