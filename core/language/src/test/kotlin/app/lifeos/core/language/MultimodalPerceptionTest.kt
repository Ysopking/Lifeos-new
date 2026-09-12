package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultimodalPerceptionTest {
    @Test
    fun `speech field is deterministic for identical pcm and context`() {
        val samples = ShortArray(8_000) { index ->
            (sin(2.0 * PI * 220.0 * index.toDouble() / 16_000.0) * 12_000.0).toInt().toShort()
        }
        val audio = Pcm16MonoAudio(16_000, samples)
        val engine = DeterministicSpeechRecognitionEngine()

        val first = engine.recognize(audio, semanticField = mapOf("PERSON" to 0.7))
        val second = engine.recognize(audio, semanticField = mapOf("PERSON" to 0.7))

        assertEquals(first, second)
        assertEquals(first.traceFingerprint, second.traceFingerprint)
        assertTrue(first.segmentCount >= 1)
    }

    @Test
    fun `writing field converges uncertain graphemes through lexical field`() {
        val lattice = GraphemeCandidateLattice(
            listOf(
                observation(0, 'b' to 0.96, 'p' to 0.30),
                observation(1, 'i' to 0.91, 'l' to 0.35),
                observation(2, 'l' to 0.82, 'i' to 0.44),
                observation(3, 'd' to 0.94, 't' to 0.32),
            )
        )

        val first = WritingFieldRecognitionEngine().recognize(lattice)
        val second = WritingFieldRecognitionEngine().recognize(lattice)

        assertEquals(first, second)
        assertEquals("bild", first.recognizedText)
        assertEquals("image", assertNotNull(first.lexicalCandidates.firstOrNull()).conceptId)
        assertTrue(first.confidence > 0.70)
    }

    @Test
    fun `perception photons are content addressed and preserve causal source`() {
        val source = Photon(
            id = PhotonId("audio-source-1"),
            content = "asset:sha256:abc",
            mimeType = "audio/pcm",
            provenance = Provenance(
                source = "microphone",
                actor = "user",
                createdAt = Instant.parse("2026-09-12T08:00:00Z"),
            ),
            tags = setOf("conversation:default", "turn:t1"),
        )
        val result = SpeechFieldRecognitionResult(
            tokens = listOf(
                SpeechRecognitionToken(0, 0, 3200, "image", "bild", 0.91, 0.87),
            ),
            recognizedText = "bild",
            confidence = 0.91,
            traceFingerprint = "a".repeat(64),
            segmentCount = 1,
        )
        val factory = PerceptionPhotonFactory()

        val first = factory.speech(source, result)
        val second = factory.speech(source, result)

        assertEquals(first.id, second.id)
        assertEquals(setOf(source.id), first.provenance.parentIds)
        assertTrue(first.relations.any { it.target == source.id && it.type == RelationType.DERIVED_FROM })
        assertTrue("trace:${result.traceFingerprint}" in first.tags)

        val utterance = factory.asUserUtterance(first, PerceptionModality.SPEECH)
        assertTrue("chat" in utterance.tags)
        assertTrue("input:speech" in utterance.tags)
        assertEquals(setOf(first.id), utterance.provenance.parentIds)
    }

    private fun observation(index: Int, vararg candidates: Pair<Char, Double>) = GraphemeObservation(
        index = index,
        candidates = candidates.map { (value, confidence) -> GraphemeCandidate(value, confidence) },
    )
}
