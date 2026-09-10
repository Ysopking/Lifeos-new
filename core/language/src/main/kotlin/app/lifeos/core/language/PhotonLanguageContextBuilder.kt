package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonPhase
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
            .filter { "goal" in it.tags && it.phase != PhotonPhase.ARCHIVED }
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
            )
        }
        return LanguageContext(items = items, activeGoalId = activeGoal, now = now)
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
