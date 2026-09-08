package app.lifeos.core.language

/** Complete model-free reference path from PCM observation into bidirectional speech fields. */
data class BidirectionalSpeechFieldResult(
    val rawAcousticLattice: AcousticPhonemeLattice,
    val lexicalField: AcousticLexicalFieldResult,
) {
    init {
        require(rawAcousticLattice.sampleRateHz == lexicalField.revisedLattice.sampleRateHz)
        require(rawAcousticLattice.frames.size == lexicalField.revisedLattice.frames.size)
    }
}

class BidirectionalSpeechFieldEngine(
    private val acousticEngine: AcousticPhonemeFieldEngine = AcousticPhonemeFieldEngine(),
    private val lexicalBridge: AcousticLexicalFieldBridge = AcousticLexicalFieldBridge(),
) {
    fun understand(
        audio: Pcm16MonoAudio,
        semanticField: Map<String, Double> = emptyMap(),
        context: LanguageContext = LanguageContext(),
    ): BidirectionalSpeechFieldResult {
        val raw = acousticEngine.analyze(audio)
        val lexical = lexicalBridge.resolve(raw, semanticField, context)
        return BidirectionalSpeechFieldResult(raw, lexical)
    }
}
