package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AcousticLexicalFieldBridgeTest {
    @Test
    fun `semantic field resolves acoustically equivalent lexemes`() {
        val lexicon = DeterministicLinguisticFieldLexicon(
            concepts = listOf(
                LinguisticConcept(
                    id = "wheel",
                    canonical = "rad",
                    variants = emptySet(),
                    semanticTag = "VEHICLE_PART",
                    semanticMass = 1.0,
                ),
                LinguisticConcept(
                    id = "advice",
                    canonical = "rat",
                    variants = emptySet(),
                    semanticTag = "ADVICE",
                    semanticMass = 1.0,
                ),
            ),
        )
        val lattice = latticeFor(listOf(
            AcousticPhonemeClass.SONORANT,
            AcousticPhonemeClass.VOICED_VOWEL,
            AcousticPhonemeClass.STOP_LIKE,
        ))
        val bridge = AcousticLexicalFieldBridge(lexicon)

        val vehicle = bridge.resolve(lattice, semanticField = mapOf("VEHICLE_PART" to 1.0))
        val advice = bridge.resolve(lattice, semanticField = mapOf("ADVICE" to 1.0))

        assertEquals("wheel", assertNotNull(vehicle.winner).conceptId)
        assertEquals("advice", assertNotNull(advice.winner).conceptId)
        assertEquals(
            vehicle.lexicalCandidates.first { it.conceptId == "wheel" }.acousticCompatibility,
            vehicle.lexicalCandidates.first { it.conceptId == "advice" }.acousticCompatibility,
        )
    }

    @Test
    fun `photon context can select one of acoustically equivalent words`() {
        val lexicon = DeterministicLinguisticFieldLexicon(
            concepts = listOf(
                LinguisticConcept("wheel", "rad", emptySet(), "VEHICLE_PART"),
                LinguisticConcept("advice", "rat", emptySet(), "ADVICE"),
            ),
        )
        val lattice = latticeFor(listOf(
            AcousticPhonemeClass.SONORANT,
            AcousticPhonemeClass.VOICED_VOWEL,
            AcousticPhonemeClass.STOP_LIKE,
        ))
        val context = LanguageContext(
            now = Instant.parse("2026-09-08T01:00:00Z"),
            items = listOf(
                LanguageContextItem(
                    photonId = PhotonId("vehicle-context"),
                    kind = "goal",
                    tags = setOf("goal"),
                    createdAt = Instant.parse("2026-09-08T00:59:00Z"),
                    active = true,
                    contentTerms = setOf("rad", "vehicle_part"),
                    confidence = 1.0,
                ),
            ),
        )

        val result = AcousticLexicalFieldBridge(lexicon).resolve(lattice, context = context)

        assertEquals("wheel", assertNotNull(result.winner).conceptId)
        assertTrue(result.winner!!.photonForce > 0.0)
    }

    @Test
    fun `lexical feedback revises candidate energy but never raw features`() {
        val lattice = latticeFor(listOf(
            AcousticPhonemeClass.SONORANT,
            AcousticPhonemeClass.VOICED_VOWEL,
            AcousticPhonemeClass.STOP_LIKE,
        ))
        val originalFeatures = lattice.frames.map { it.features }
        val lexicon = DeterministicLinguisticFieldLexicon(
            concepts = listOf(LinguisticConcept("wheel", "rad", emptySet(), "VEHICLE_PART")),
        )

        val result = AcousticLexicalFieldBridge(lexicon).resolve(
            lattice,
            semanticField = mapOf("VEHICLE_PART" to 1.0),
        )

        assertTrue(result.revisions.isNotEmpty())
        assertEquals(originalFeatures, result.revisedLattice.frames.map { it.features })
        assertTrue(result.revisions.all { it.afterActivation >= it.beforeActivation })
    }

    @Test
    fun `default lexicon can acoustically surface football`() {
        val encoder = CoarsePhonemeTemplateEncoder()
        val template = encoder.encode("fussball")
        val lattice = latticeFor(template)

        val result = AcousticLexicalFieldBridge().resolve(
            lattice,
            semanticField = mapOf("FOOTBALL" to 1.0, "ACTION_PLAY" to 0.9),
        )

        assertEquals("football", assertNotNull(result.winner).conceptId)
        assertTrue(result.winner!!.acousticCompatibility > 0.60)
    }

    @Test
    fun `bidirectional resolution is deterministic`() {
        val template = CoarsePhonemeTemplateEncoder().encode("fussball")
        val lattice = latticeFor(template)
        val bridge = AcousticLexicalFieldBridge()

        val first = bridge.resolve(lattice, semanticField = mapOf("FOOTBALL" to 0.9))
        val second = bridge.resolve(lattice, semanticField = mapOf("FOOTBALL" to 0.9))

        assertEquals(first, second)
    }

    private fun latticeFor(template: List<AcousticPhonemeClass>): AcousticPhonemeLattice {
        require(template.isNotEmpty())
        val frameCount = (template.size * 2).coerceAtLeast(3)
        val frames = List(frameCount) { frameIndex ->
            val expectedIndex = if (template.size == 1 || frameCount <= 1) 0 else {
                ((frameIndex.toDouble() / (frameCount - 1).toDouble()) * (template.size - 1)).toInt().coerceIn(template.indices)
            }
            val expected = template[expectedIndex]
            val alternatives = AcousticPhonemeClass.entries
                .filter { it != AcousticPhonemeClass.SILENCE && it != expected }
                .take(3)
            val candidates = buildList {
                add(AcousticPhonemeCandidate(expected, 0.76, 0.12, 0.0))
                alternatives.forEachIndexed { index, value ->
                    add(AcousticPhonemeCandidate(value, 0.34 - index * 0.04, 0.45 + index * 0.05, 0.0))
                }
            }
            AcousticPhonemeFrame(
                frameIndex = frameIndex,
                features = AcousticFrameFeatures(
                    frameIndex = frameIndex,
                    startSample = frameIndex * 160,
                    rms = 0.2,
                    zeroCrossingRate = 0.15,
                    spectralCentroidHz = 1_200.0,
                    lowBandFraction = 0.3,
                    midBandFraction = 0.5,
                    highBandFraction = 0.2,
                    voicing = 0.7,
                ),
                candidates = candidates,
            )
        }
        return AcousticPhonemeLattice(
            sampleRateHz = 16_000,
            frameLengthSamples = 320,
            hopLengthSamples = 160,
            frames = frames,
            totalEnergy = frames.sumOf { it.features.rms },
        )
    }
}
