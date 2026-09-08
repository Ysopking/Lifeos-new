package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhraseFieldDecoderTest {
    @Test
    fun `continuous speech run decodes multiple words without a silence boundary`() {
        val lattice = phraseLattice("erzeugen", "bild")

        val result = PhraseFieldDecoder().decode(
            lattice,
            semanticField = mapOf("CREATE" to 1.0, "IMAGE" to 1.0),
        )

        val winner = assertNotNull(result.winner)
        assertEquals(listOf("erzeugen", "bild"), winner.words.map { it.canonical })
        assertEquals("erzeugen bild", winner.transcript)
        assertTrue(winner.coverage > 0.90)
        assertTrue(winner.coherence > 0.70)
    }

    @Test
    fun `photon context resolves an acoustically identical phrase word`() {
        val lexicon = DeterministicLinguisticFieldLexicon(
            concepts = listOf(
                LinguisticConcept("wheel", "rad", emptySet(), "VEHICLE_PART"),
                LinguisticConcept("advice", "rat", emptySet(), "ADVICE"),
            ),
        )
        val context = LanguageContext(
            now = Instant.parse("2026-09-08T01:00:00Z"),
            items = listOf(
                LanguageContextItem(
                    photonId = PhotonId("wheel-context"),
                    kind = "goal",
                    tags = setOf("goal"),
                    createdAt = Instant.parse("2026-09-08T00:59:00Z"),
                    active = true,
                    contentTerms = setOf("rad", "vehicle_part"),
                    confidence = 1.0,
                ),
            ),
        )
        val decoder = PhraseFieldDecoder(lexicon)
        val lattice = phraseLattice("rad")

        val result = decoder.decode(lattice, context = context)

        val winner = assertNotNull(result.winner)
        assertEquals("rad", winner.transcript)
        assertTrue(winner.words.single().photonForce > 0.0)
    }

    @Test
    fun `phrase feedback revises interpretation but never raw acoustic features`() {
        val lattice = phraseLattice("spielen", "fussball")
        val rawFeatures = lattice.frames.map { it.features }
        val rawCandidates = lattice.frames.map { it.candidates }

        val result = PhraseFieldDecoder().decode(
            lattice,
            semanticField = mapOf("ACTION_PLAY" to 1.0, "FOOTBALL" to 1.0),
        )

        assertTrue(result.revisions.isNotEmpty())
        assertEquals(rawFeatures, result.rawLattice.frames.map { it.features })
        assertEquals(rawFeatures, result.revisedLattice.frames.map { it.features })
        assertEquals(rawCandidates, result.rawLattice.frames.map { it.candidates })
        assertTrue(result.revisions.all { it.afterActivation >= it.beforeActivation })
    }

    @Test
    fun `unknown acoustic frames may remain unresolved without destroying known words`() {
        val first = framesForWord("erzeugen", startFrameIndex = 0)
        val unknownStart = first.size
        val unknown = List(5) { offset ->
            frame(
                frameIndex = unknownStart + offset,
                expected = AcousticPhonemeClass.UNKNOWN,
                expectedActivation = 0.90,
            )
        }
        val second = framesForWord("bild", startFrameIndex = unknownStart + unknown.size)
        val lattice = lattice(first + unknown + second)

        val result = PhraseFieldDecoder().decode(
            lattice,
            semanticField = mapOf("CREATE" to 1.0, "IMAGE" to 1.0),
        )

        val winner = assertNotNull(result.winner)
        assertTrue(winner.words.any { it.canonical == "erzeugen" })
        assertTrue(winner.words.any { it.canonical == "bild" })
        assertTrue(winner.unresolvedSpeechFrames > 0)
    }

    @Test
    fun `phrase decoding and top down revision are deterministic`() {
        val lattice = phraseLattice("erzeugen", "bild", "fussball")
        val decoder = PhraseFieldDecoder()
        val field = mapOf("CREATE" to 0.9, "IMAGE" to 1.0, "FOOTBALL" to 0.95)

        val first = decoder.decode(lattice, semanticField = field)
        val second = decoder.decode(lattice, semanticField = field)

        assertEquals(first, second)
    }

    private fun phraseLattice(vararg words: String): AcousticPhonemeLattice {
        val frames = mutableListOf<AcousticPhonemeFrame>()
        words.forEach { word ->
            frames += framesForWord(word, startFrameIndex = frames.size)
        }
        return lattice(frames)
    }

    private fun framesForWord(word: String, startFrameIndex: Int): List<AcousticPhonemeFrame> {
        val template = CoarsePhonemeTemplateEncoder().encode(word)
        require(template.isNotEmpty())
        val frameCount = (template.size * 4).coerceAtLeast(4)
        return List(frameCount) { offset ->
            val expectedIndex = if (template.size == 1 || frameCount <= 1) {
                0
            } else {
                ((offset.toDouble() / (frameCount - 1).toDouble()) * (template.size - 1))
                    .toInt()
                    .coerceIn(template.indices)
            }
            frame(
                frameIndex = startFrameIndex + offset,
                expected = template[expectedIndex],
                expectedActivation = 0.94,
            )
        }
    }

    private fun frame(
        frameIndex: Int,
        expected: AcousticPhonemeClass,
        expectedActivation: Double,
    ): AcousticPhonemeFrame {
        val alternatives = AcousticPhonemeClass.entries
            .filter { it != AcousticPhonemeClass.SILENCE && it != expected }
            .take(3)
        return AcousticPhonemeFrame(
            frameIndex = frameIndex,
            features = AcousticFrameFeatures(
                frameIndex = frameIndex,
                startSample = frameIndex * 160,
                rms = 0.20,
                zeroCrossingRate = 0.15,
                spectralCentroidHz = 1_200.0,
                lowBandFraction = 0.30,
                midBandFraction = 0.50,
                highBandFraction = 0.20,
                voicing = 0.70,
            ),
            candidates = buildList {
                add(AcousticPhonemeCandidate(expected, expectedActivation, 0.08, 0.0))
                alternatives.forEachIndexed { index, alternative ->
                    add(
                        AcousticPhonemeCandidate(
                            alternative,
                            0.18 - index * 0.03,
                            0.62 + index * 0.05,
                            0.0,
                        ),
                    )
                }
            },
        )
    }

    private fun lattice(frames: List<AcousticPhonemeFrame>): AcousticPhonemeLattice =
        AcousticPhonemeLattice(
            sampleRateHz = 16_000,
            frameLengthSamples = 320,
            hopLengthSamples = 160,
            frames = frames,
            totalEnergy = frames.sumOf { it.features.rms },
        )
}
