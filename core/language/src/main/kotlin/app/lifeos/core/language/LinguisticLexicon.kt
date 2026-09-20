package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds
import java.util.concurrent.atomic.AtomicReference

interface LinguisticLexicon {
    val concepts: List<LinguisticConcept>
    val snapshotFingerprint: String? get() = null
    fun byId(id: String): LinguisticConcept?
}

data class LinguisticLexiconSnapshot private constructor(
    val revision: Long,
    override val concepts: List<LinguisticConcept>,
    val predecessorFingerprint: String?,
    val promotionEvidenceFingerprint: String?,
    val fingerprint: String,
) : LinguisticLexicon {
    private val byId = concepts.associateBy { it.id }

    init {
        require(revision > 0L)
        require(concepts.isNotEmpty())
        require(concepts.map { it.id }.distinct().size == concepts.size)
        require(concepts.map { it.id } == concepts.map { it.id }.sorted()) {
            "Linguistic lexicon concepts must use canonical id order"
        }
        require(predecessorFingerprint == null || predecessorFingerprint.isNotBlank())
        require(promotionEvidenceFingerprint == null || promotionEvidenceFingerprint.isNotBlank())
        require(fingerprint == expectedFingerprint(
            revision,
            concepts,
            predecessorFingerprint,
            promotionEvidenceFingerprint,
        ))
    }

    override val snapshotFingerprint: String get() = fingerprint

    override fun byId(id: String): LinguisticConcept? = byId[id]

    companion object {
        fun create(
            revision: Long,
            concepts: Collection<LinguisticConcept>,
            predecessorFingerprint: String? = null,
            promotionEvidenceFingerprint: String? = null,
        ): LinguisticLexiconSnapshot {
            val canonical = concepts.sortedBy { it.id }
            return LinguisticLexiconSnapshot(
                revision = revision,
                concepts = canonical,
                predecessorFingerprint = predecessorFingerprint,
                promotionEvidenceFingerprint = promotionEvidenceFingerprint,
                fingerprint = expectedFingerprint(
                    revision,
                    canonical,
                    predecessorFingerprint,
                    promotionEvidenceFingerprint,
                ),
            )
        }

        fun builtin(): LinguisticLexiconSnapshot = create(
            revision = 1L,
            concepts = DeterministicLinguisticFieldLexicon().concepts,
            promotionEvidenceFingerprint = "builtin",
        )

        private fun expectedFingerprint(
            revision: Long,
            concepts: List<LinguisticConcept>,
            predecessorFingerprint: String?,
            promotionEvidenceFingerprint: String?,
        ): String = StableCognitiveIds.fingerprint(
            "linguistic-lexicon-snapshot/v1",
            revision.toString(),
            predecessorFingerprint.orEmpty(),
            promotionEvidenceFingerprint.orEmpty(),
            *concepts.flatMap(::conceptFingerprintParts).toTypedArray(),
        )

        private fun conceptFingerprintParts(concept: LinguisticConcept): List<String> = buildList {
            add(concept.id)
            add(concept.canonical)
            add(concept.semanticTag)
            add(concept.entityType?.name.orEmpty())
            add(java.lang.Double.toHexString(concept.semanticMass))
            addAll(concept.variants.sorted().map { "variant:$it" })
            addAll(concept.attractsTags.sorted().map { "attract:$it" })
            addAll(concept.repelsTags.sorted().map { "repel:$it" })
            addAll(concept.intentBias.entries.sortedBy { it.key.name }.map {
                "intent:${it.key.name}:${java.lang.Double.toHexString(it.value)}"
            })
        }
    }
}

data class LanguageRuntimeSnapshot(
    val lexicon: LinguisticLexiconSnapshot,
    val understanding: LanguageUnderstandingEngine,
    val responseGeneration: LanguageResponseGenerationEngine,
    val speechEngine: BidirectionalSpeechFieldEngine,
    val phraseDecoder: PhraseFieldDecoder,
)

class VersionedLanguageRuntime(
    initial: LinguisticLexiconSnapshot = LinguisticLexiconSnapshot.builtin(),
) {
    private val active = AtomicReference(build(initial))
    private val history = linkedMapOf(initial.fingerprint to active.get())

    fun current(): LanguageRuntimeSnapshot = active.get()

    fun nextSnapshot(
        concepts: Collection<LinguisticConcept>,
        promotionEvidenceFingerprint: String,
    ): LinguisticLexiconSnapshot {
        require(promotionEvidenceFingerprint.isNotBlank())
        val previous = active.get()
        return LinguisticLexiconSnapshot.create(
            revision = previous.lexicon.revision + 1L,
            concepts = concepts,
            predecessorFingerprint = previous.lexicon.fingerprint,
            promotionEvidenceFingerprint = promotionEvidenceFingerprint,
        )
    }

    @Synchronized
    fun install(snapshot: LinguisticLexiconSnapshot): LanguageRuntimeSnapshot {
        val next = build(snapshot)
        history[snapshot.fingerprint] = next
        active.set(next)
        return next
    }

    @Synchronized
    fun promote(
        concepts: Collection<LinguisticConcept>,
        promotionEvidenceFingerprint: String,
    ): LanguageRuntimeSnapshot = install(
        nextSnapshot(concepts, promotionEvidenceFingerprint)
    )

    @Synchronized
    fun rollback(targetFingerprint: String): LanguageRuntimeSnapshot {
        val target = requireNotNull(history[targetFingerprint]) {
            "Unknown language runtime snapshot: $targetFingerprint"
        }
        active.set(target)
        return target
    }

    private fun build(lexicon: LinguisticLexiconSnapshot): LanguageRuntimeSnapshot {
        val field = LinguisticFieldEngine(lexicon = lexicon)
        val understanding = LanguageUnderstandingEngine(linguisticFieldEngine = field)
        return LanguageRuntimeSnapshot(
            lexicon = lexicon,
            understanding = understanding,
            responseGeneration = LanguageResponseGenerationEngine(understanding = understanding),
            speechEngine = BidirectionalSpeechFieldEngine(
                lexicalBridge = AcousticLexicalFieldBridge(lexicon = lexicon),
            ),
            phraseDecoder = PhraseFieldDecoder(lexicon = lexicon),
        )
    }
}
