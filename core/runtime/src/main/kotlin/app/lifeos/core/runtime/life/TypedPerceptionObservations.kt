package app.lifeos.core.runtime.life

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

enum class PerceptionModality {
    TEXT,
    SPEECH,
    WRITING,
    VISUAL,
}

data class PerceptionCandidate(
    val value: String,
    val confidence: Double,
    val semanticTag: String? = null,
) {
    init {
        require(value.isNotBlank()) { "Perception candidate value must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(semanticTag == null || semanticTag.isNotBlank())
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "perception-candidate/v1",
        value,
        semanticTag.orEmpty(),
        java.lang.Double.toHexString(confidence),
    )
}

data class SpeechWordObservation(
    val index: Int,
    val candidates: List<PerceptionCandidate>,
) {
    init {
        require(index >= 0)
        require(candidates.isNotEmpty())
        require(candidates.map { it.value to it.semanticTag }.distinct().size == candidates.size) {
            "Speech word candidates must be unique"
        }
    }

    val canonicalCandidates: List<PerceptionCandidate> = candidates.sortedWith(
        compareByDescending<PerceptionCandidate> { it.confidence }
            .thenBy { it.value }
            .thenBy { it.semanticTag.orEmpty() }
    )

    val winner: PerceptionCandidate get() = canonicalCandidates.first()
}

data class SpeechObservation(
    val sourceId: String,
    val observedAt: Instant,
    val observedUntil: Instant,
    val words: List<SpeechWordObservation>,
    val sourceAssetPhotonId: PhotonId? = null,
    val salience: Double = 0.8,
    val tags: Set<String> = emptySet(),
) {
    init {
        require(sourceId.isNotBlank())
        require(!observedUntil.isBefore(observedAt))
        require(words.isNotEmpty())
        require(words.map { it.index } == words.indices.toList()) {
            "Speech word observations must be contiguous and ordered"
        }
        require(salience.isFinite() && salience in 0.0..1.0)
    }

    val transcript: String = words.joinToString(" ") { it.winner.value }
    val confidence: Double = words.map { it.winner.confidence }.average().coerceIn(0.0, 1.0)
    val candidates: List<PerceptionCandidate> = words.flatMap { word ->
        word.canonicalCandidates.map { candidate ->
            candidate.copy(value = "${word.index}:${candidate.value}")
        }
    }

    fun toSignal(): PerceptionSignal = PerceptionSignal(
        source = PerceptionSource.SENSOR,
        sourceId = sourceId,
        observedAt = observedAt,
        payload = transcript,
        mimeType = "text/plain",
        confidence = confidence,
        salience = salience,
        tags = tags + setOf("speech-observation"),
        modality = PerceptionModality.SPEECH,
        observedUntil = observedUntil,
        candidates = candidates,
        sourceAssetPhotonId = sourceAssetPhotonId,
    )
}

data class GraphemeObservation(
    val index: Int,
    val candidates: List<PerceptionCandidate>,
) {
    init {
        require(index >= 0)
        require(candidates.isNotEmpty())
        require(candidates.map { it.value }.distinct().size == candidates.size) {
            "Grapheme candidates must be unique per observation"
        }
        require(candidates.all { it.value.length == 1 }) {
            "Grapheme candidates must contain one Unicode code unit"
        }
    }

    val canonicalCandidates: List<PerceptionCandidate> = candidates.sortedWith(
        compareByDescending<PerceptionCandidate> { it.confidence }.thenBy { it.value }
    )

    val winner: PerceptionCandidate get() = canonicalCandidates.first()
}

data class WritingObservation(
    val sourceId: String,
    val observedAt: Instant,
    val observedUntil: Instant,
    val graphemes: List<GraphemeObservation>,
    val sourceAssetPhotonId: PhotonId? = null,
    val salience: Double = 0.75,
    val tags: Set<String> = emptySet(),
) {
    init {
        require(sourceId.isNotBlank())
        require(!observedUntil.isBefore(observedAt))
        require(graphemes.isNotEmpty())
        require(graphemes.map { it.index } == graphemes.indices.toList()) {
            "Grapheme observations must be contiguous and ordered"
        }
        require(salience.isFinite() && salience in 0.0..1.0)
    }

    val recognizedText: String = graphemes.joinToString("") { it.winner.value }
    val confidence: Double = graphemes.map { it.winner.confidence }.average().coerceIn(0.0, 1.0)
    val candidates: List<PerceptionCandidate> = graphemes.flatMap { grapheme ->
        grapheme.canonicalCandidates.map { candidate ->
            candidate.copy(value = "${grapheme.index}:${candidate.value}")
        }
    }

    fun toSignal(): PerceptionSignal = PerceptionSignal(
        source = PerceptionSource.FILE,
        sourceId = sourceId,
        observedAt = observedAt,
        payload = recognizedText,
        mimeType = "text/plain",
        confidence = confidence,
        salience = salience,
        tags = tags + setOf("writing-observation"),
        modality = PerceptionModality.WRITING,
        observedUntil = observedUntil,
        candidates = candidates,
        sourceAssetPhotonId = sourceAssetPhotonId,
    )
}

data class VisualObservation(
    val sourceId: String,
    val observedAt: Instant,
    val observedUntil: Instant,
    val candidates: List<PerceptionCandidate>,
    val sourceAssetPhotonId: PhotonId,
    val frameKey: String,
    val confidence: Double = candidates.maxOfOrNull { it.confidence } ?: 0.0,
    val salience: Double = 0.6,
    val tags: Set<String> = emptySet(),
) {
    init {
        require(sourceId.isNotBlank())
        require(frameKey.isNotBlank())
        require(!observedUntil.isBefore(observedAt))
        require(candidates.isNotEmpty())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(salience.isFinite() && salience in 0.0..1.0)
    }

    fun toSignal(): PerceptionSignal = PerceptionSignal(
        source = PerceptionSource.SENSOR,
        sourceId = sourceId,
        observedAt = observedAt,
        payload = frameKey,
        mimeType = "application/vnd.lifeos.visual-observation+text",
        confidence = confidence,
        salience = salience,
        tags = tags + setOf("visual-observation", "frame:$frameKey"),
        modality = PerceptionModality.VISUAL,
        observedUntil = observedUntil,
        candidates = candidates,
        sourceAssetPhotonId = sourceAssetPhotonId,
    )
}
