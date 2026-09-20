package app.lifeos.core.runtime.personal

import app.lifeos.core.language.EntityType
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageRuntimeSnapshot
import app.lifeos.core.language.LinguisticConcept
import app.lifeos.core.language.LinguisticLexiconSnapshot
import app.lifeos.core.language.VersionedLanguageRuntime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class DurableLanguageRuntimeHead(
    val headRevision: Long,
    val activeSnapshotFingerprint: String,
    val activeLexiconRevision: Long,
    val previousActiveSnapshotFingerprint: String?,
) {
    init {
        require(headRevision > 0L)
        require(activeSnapshotFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Language runtime head requires a canonical snapshot fingerprint"
        }
        require(activeLexiconRevision > 0L)
        require(
            previousActiveSnapshotFingerprint == null ||
                previousActiveSnapshotFingerprint.matches(Regex("[0-9a-f]{64}"))
        )
    }
}

interface LanguageRuntimeStateRepository {
    suspend fun saveSnapshot(snapshot: LinguisticLexiconSnapshot)
    suspend fun loadSnapshot(fingerprint: String): LinguisticLexiconSnapshot?
    suspend fun loadHead(): DurableLanguageRuntimeHead?
    suspend fun compareAndSetHead(
        expectedRevision: Long?,
        next: DurableLanguageRuntimeHead,
    ): Boolean
}

object LanguageRuntimeStateCodec {
    const val MAX_SNAPSHOT_BYTES: Int = 2 * 1024 * 1024
    const val MAX_HEAD_BYTES: Int = 64 * 1024

    private const val SNAPSHOT_VERSION = 1
    private const val HEAD_VERSION = 1
    private const val MAX_CONCEPTS = 4096
    private const val MAX_SET_ENTRIES = 512

    fun encodeSnapshot(snapshot: LinguisticLexiconSnapshot): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(SNAPSHOT_VERSION)
                data.writeLong(snapshot.revision)
                data.writeUTF(snapshot.predecessorFingerprint.orEmpty())
                data.writeUTF(snapshot.promotionEvidenceFingerprint.orEmpty())
                data.writeUTF(snapshot.fingerprint)
                data.writeInt(snapshot.concepts.size)
                snapshot.concepts.forEach { concept ->
                    data.writeUTF(concept.id)
                    data.writeUTF(concept.canonical)
                    data.writeUTF(concept.semanticTag)
                    data.writeUTF(concept.entityType?.name.orEmpty())
                    data.writeDouble(concept.semanticMass)
                    data.writeStrings(concept.variants)
                    data.writeStrings(concept.attractsTags)
                    data.writeStrings(concept.repelsTags)
                    val intents = concept.intentBias.entries.sortedBy { it.key.name }
                    data.writeInt(intents.size)
                    intents.forEach { (intent, bias) ->
                        data.writeUTF(intent.name)
                        data.writeDouble(bias)
                    }
                }
            }
            output.toByteArray()
        }.also { payload ->
            require(payload.size in 1..MAX_SNAPSHOT_BYTES) {
                "Language snapshot payload is outside bounded size"
            }
        }

    fun decodeSnapshot(bytes: ByteArray): LinguisticLexiconSnapshot {
        require(bytes.size in 1..MAX_SNAPSHOT_BYTES) {
            "Language snapshot payload is outside bounded size"
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == SNAPSHOT_VERSION) {
                "Unsupported language snapshot codec version"
            }
            val revision = input.readLong()
            val predecessor = input.readUTF().ifBlank { null }
            val promotion = input.readUTF().ifBlank { null }
            val expectedFingerprint = input.readUTF()
            val conceptCount = input.readBoundedCount(MAX_CONCEPTS)
            val concepts = List(conceptCount) {
                val id = input.readUTF()
                val canonical = input.readUTF()
                val semanticTag = input.readUTF()
                val entityName = input.readUTF()
                val semanticMass = input.readDouble()
                val variants = input.readStrings()
                val attracts = input.readStrings()
                val repels = input.readStrings()
                val intentCount = input.readBoundedCount(IntentType.entries.size)
                val intentBias = buildMap {
                    repeat(intentCount) {
                        val intent = IntentType.valueOf(input.readUTF())
                        val bias = input.readDouble()
                        require(put(intent, bias) == null) {
                            "Duplicate intent bias in language snapshot"
                        }
                    }
                }
                LinguisticConcept(
                    id = id,
                    canonical = canonical,
                    variants = variants,
                    semanticTag = semanticTag,
                    entityType = entityName.takeIf(String::isNotBlank)?.let(EntityType::valueOf),
                    intentBias = intentBias,
                    attractsTags = attracts,
                    repelsTags = repels,
                    semanticMass = semanticMass,
                )
            }
            require(input.available() == 0) { "Trailing bytes in language snapshot payload" }
            LinguisticLexiconSnapshot.create(
                revision = revision,
                concepts = concepts,
                predecessorFingerprint = predecessor,
                promotionEvidenceFingerprint = promotion,
            ).also { decoded ->
                require(decoded.fingerprint == expectedFingerprint) {
                    "Language snapshot fingerprint mismatch"
                }
            }
        }
    }

    fun encodeHead(head: DurableLanguageRuntimeHead): ByteArray =
        ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(HEAD_VERSION)
                data.writeLong(head.headRevision)
                data.writeUTF(head.activeSnapshotFingerprint)
                data.writeLong(head.activeLexiconRevision)
                data.writeUTF(head.previousActiveSnapshotFingerprint.orEmpty())
            }
            output.toByteArray()
        }.also { payload ->
            require(payload.size in 1..MAX_HEAD_BYTES)
        }

    fun decodeHead(bytes: ByteArray): DurableLanguageRuntimeHead {
        require(bytes.size in 1..MAX_HEAD_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == HEAD_VERSION) {
                "Unsupported language runtime head codec version"
            }
            val head = DurableLanguageRuntimeHead(
                headRevision = input.readLong(),
                activeSnapshotFingerprint = input.readUTF(),
                activeLexiconRevision = input.readLong(),
                previousActiveSnapshotFingerprint = input.readUTF().ifBlank { null },
            )
            require(input.available() == 0) { "Trailing bytes in language runtime head" }
            head
        }
    }

    private fun DataOutputStream.writeStrings(values: Set<String>) {
        val canonical = values.sorted()
        require(canonical.size <= MAX_SET_ENTRIES)
        writeInt(canonical.size)
        canonical.forEach(::writeUTF)
    }

    private fun DataInputStream.readStrings(): Set<String> {
        val count = readBoundedCount(MAX_SET_ENTRIES)
        return buildSet {
            repeat(count) {
                require(add(readUTF())) { "Duplicate string in language snapshot" }
            }
        }
    }

    private fun DataInputStream.readBoundedCount(max: Int): Int =
        readInt().also { require(it in 0..max) { "Invalid bounded language snapshot count" } }
}

class DurableLanguageRuntimeCoordinator(
    private val runtime: VersionedLanguageRuntime,
    private val repository: LanguageRuntimeStateRepository,
) {
    fun current(): LanguageRuntimeSnapshot = runtime.current()

    suspend fun rehydrate(): LanguageRuntimeSnapshot {
        val head = repository.loadHead() ?: return initializeBuiltin()
        val snapshot = requireNotNull(repository.loadSnapshot(head.activeSnapshotFingerprint)) {
            "Language runtime head points to missing snapshot"
        }
        require(snapshot.revision == head.activeLexiconRevision) {
            "Language runtime head/snapshot revision mismatch"
        }
        return runtime.install(snapshot)
    }

    suspend fun promote(
        concepts: Collection<LinguisticConcept>,
        promotionEvidenceFingerprint: String,
    ): LanguageRuntimeSnapshot {
        val currentHead = ensureCurrentHead()
        require(runtime.current().lexicon.fingerprint == currentHead.activeSnapshotFingerprint) {
            "In-memory language runtime differs from durable head"
        }

        val candidate = runtime.nextSnapshot(concepts, promotionEvidenceFingerprint)
        repository.saveSnapshot(candidate)
        val nextHead = DurableLanguageRuntimeHead(
            headRevision = currentHead.headRevision + 1L,
            activeSnapshotFingerprint = candidate.fingerprint,
            activeLexiconRevision = candidate.revision,
            previousActiveSnapshotFingerprint = currentHead.activeSnapshotFingerprint,
        )
        require(repository.compareAndSetHead(currentHead.headRevision, nextHead)) {
            "Language runtime head CAS conflict during promotion"
        }
        return runtime.install(candidate)
    }

    suspend fun rollback(targetSnapshotFingerprint: String): LanguageRuntimeSnapshot {
        val currentHead = ensureCurrentHead()
        require(targetSnapshotFingerprint != currentHead.activeSnapshotFingerprint) {
            "Language runtime rollback target is already active"
        }
        val target = requireNotNull(repository.loadSnapshot(targetSnapshotFingerprint)) {
            "Language runtime rollback target does not exist"
        }
        val nextHead = DurableLanguageRuntimeHead(
            headRevision = currentHead.headRevision + 1L,
            activeSnapshotFingerprint = target.fingerprint,
            activeLexiconRevision = target.revision,
            previousActiveSnapshotFingerprint = currentHead.activeSnapshotFingerprint,
        )
        require(repository.compareAndSetHead(currentHead.headRevision, nextHead)) {
            "Language runtime head CAS conflict during rollback"
        }
        return runtime.install(target)
    }

    suspend fun currentHead(): DurableLanguageRuntimeHead = ensureCurrentHead()

    private suspend fun initializeBuiltin(): LanguageRuntimeSnapshot {
        val builtin = runtime.current().lexicon
        repository.saveSnapshot(builtin)
        val initialHead = DurableLanguageRuntimeHead(
            headRevision = 1L,
            activeSnapshotFingerprint = builtin.fingerprint,
            activeLexiconRevision = builtin.revision,
            previousActiveSnapshotFingerprint = null,
        )
        if (repository.compareAndSetHead(expectedRevision = null, next = initialHead)) {
            return runtime.install(builtin)
        }

        val durableWinner = requireNotNull(repository.loadHead()) {
            "Language runtime initialization lost CAS but durable head is missing"
        }
        val winnerSnapshot = requireNotNull(
            repository.loadSnapshot(durableWinner.activeSnapshotFingerprint)
        ) {
            "Language runtime initialization winner points to missing snapshot"
        }
        require(winnerSnapshot.revision == durableWinner.activeLexiconRevision)
        return runtime.install(winnerSnapshot)
    }

    private suspend fun ensureCurrentHead(): DurableLanguageRuntimeHead =
        repository.loadHead() ?: run {
            initializeBuiltin()
            requireNotNull(repository.loadHead())
        }
}
