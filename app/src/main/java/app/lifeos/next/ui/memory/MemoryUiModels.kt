package app.lifeos.next.ui.memory

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.life.MemoryAtomKind
import app.lifeos.core.runtime.life.MemoryStage
import java.time.Instant

enum class MemoryWorkspaceTab(val label: String) {
    NOW("Jetzt"),
    TOPICS("Themen"),
    TIMELINE("Timeline"),
}

data class MemorySourceUi(
    val photonId: PhotonId,
    val content: String,
    val mimeType: String,
    val stage: MemoryStage?,
    val isNew: Boolean,
    val confidence: Double,
    val createdAt: Instant,
    val source: String,
    val actor: String,
    val tags: Set<String>,
    val parentCount: Int,
    val relationCount: Int,
) {
    val isImage: Boolean
        get() = mimeType == IMAGE_REFERENCE_MIME

    companion object {
        const val IMAGE_REFERENCE_MIME = "application/vnd.lifeos.image-ref+text"
    }
}

data class MemoryAtomUi(
    val atomId: String,
    val kind: MemoryAtomKind,
    val content: String,
    val stage: MemoryStage,
    val confidence: Double,
    val observedAt: Instant,
    val sourcePhotonIds: Set<PhotonId>,
    val resolvedSourceCount: Int,
    val missingSourceCount: Int,
)

data class MemoryTopicGroupUi(
    val kind: MemoryAtomKind,
    val label: String,
    val atoms: List<MemoryAtomUi>,
)

data class MemoryCrystalUi(
    val crystalId: String,
    val semanticCore: String,
    val confidence: Double,
    val startedAt: Instant,
    val endedAt: Instant,
    val sourcePhotonIds: Set<PhotonId>,
    val resolvedSourceCount: Int,
    val missingSourceCount: Int,
)

data class MemoryEpisodeUi(
    val episodeId: String,
    val stage: MemoryStage,
    val startedAt: Instant,
    val endedAt: Instant,
    val semanticKeys: Set<String>,
    val sourcePhotonIds: Set<PhotonId>,
    val resolvedSourceCount: Int,
    val missingSourceCount: Int,
)

data class MemoryWorkspaceUiModel(
    val projectionAvailable: Boolean,
    val projectionEvaluatedAt: Instant?,
    val authoritativePhotonCount: Int,
    val now: List<MemorySourceUi>,
    val topicGroups: List<MemoryTopicGroupUi>,
    val crystals: List<MemoryCrystalUi>,
    val episodes: List<MemoryEpisodeUi>,
    val query: String,
) {
    companion object {
        fun empty(): MemoryWorkspaceUiModel = MemoryWorkspaceUiModel(
            projectionAvailable = false,
            projectionEvaluatedAt = null,
            authoritativePhotonCount = 0,
            now = emptyList(),
            topicGroups = emptyList(),
            crystals = emptyList(),
            episodes = emptyList(),
            query = "",
        )
    }
}

fun MemoryAtomKind.germanLabel(): String = when (this) {
    MemoryAtomKind.PERSON -> "Personen"
    MemoryAtomKind.RELATIONSHIP -> "Beziehungen"
    MemoryAtomKind.EVENT -> "Ereignisse"
    MemoryAtomKind.STATEMENT -> "Aussagen"
    MemoryAtomKind.OBLIGATION -> "Verpflichtungen"
    MemoryAtomKind.AMOUNT -> "Beträge"
    MemoryAtomKind.DEADLINE -> "Fristen"
    MemoryAtomKind.PLACE -> "Orte"
    MemoryAtomKind.DECISION -> "Entscheidungen"
    MemoryAtomKind.OUTCOME -> "Ergebnisse"
    MemoryAtomKind.GOAL -> "Ziele"
    MemoryAtomKind.FACT -> "Fakten"
}

fun MemoryStage.germanLabel(): String = when (this) {
    MemoryStage.HOT -> "Aktiv"
    MemoryStage.WARM -> "Nah"
    MemoryStage.COLD -> "Langzeit"
    MemoryStage.CRYSTALLIZED -> "Kristallisiert"
}
