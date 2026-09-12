package app.lifeos.core.runtime.capability

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultimodalPerceptionCapabilitiesTest {
    @Test
    fun `local perception fields are system trusted executable providers`() = runTest {
        val registry = CapabilityRegistry(MultimodalPerceptionCapabilities.LOCAL_FIELD_PROVIDERS)

        val word = registry.providersFor(CapabilityId(MultimodalPerceptionCapabilities.WORD_FIELD)).single()
        val speech = registry.providersFor(CapabilityId(MultimodalPerceptionCapabilities.SPEECH_FIELD)).single()
        val writing = registry.providersFor(CapabilityId(MultimodalPerceptionCapabilities.WRITING_FIELD)).single()

        assertEquals(TrustLevel.SYSTEM, word.trustLevel)
        assertEquals(setOf("lexical-semantic-field"), word.contract.outputs)
        assertEquals(setOf("pcm16-mono"), speech.contract.requiredInputs)
        assertEquals(setOf("speech-field-result"), speech.contract.outputs)
        assertEquals(setOf("grapheme-candidate-lattice"), writing.contract.requiredInputs)
        assertEquals(setOf("writing-field-result"), writing.contract.outputs)
        assertTrue(registry.providersFor(CapabilityId(MultimodalPerceptionCapabilities.VISUAL_WRITING)).isEmpty())
    }
}
