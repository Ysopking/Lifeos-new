package app.lifeos.core.runtime.personal

import app.lifeos.core.language.EntityType
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageRuntimeSnapshot
import app.lifeos.core.language.LinguisticConcept
import app.lifeos.core.language.LinguisticLexiconSnapshot
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.Base64

internal object LinguisticLexiconSnapshotCodec {
    private const val VERSION = 1
    private const val MAX_CONCEPTS = 4096
    private const val MAX_SET_ENTRIES = 512
    private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

    fun encode(snapshot: LinguisticLexiconSnapshot): String {
        val payload = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(VERSION)
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
        }
        require(payload.size in 1..MAX_PAYLOAD_BYTES) {
            "Language snapshot payload is outside bounded size"
        }
        return "language-lexicon-snapshot/v1\n" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    fun decode(content: String): LinguisticLexiconSnapshot {
        val lines = content.lineSequence().toList()
        require(lines.size == 2 && lines[0] == "language-lexicon-snapshot/v1") {
            "Unsupported language snapshot payload"
        }
        val payload = Base64.getUrlDecoder().decode(lines[1])
        require(payload.size in 1..MAX_PAYLOAD_BYTES) {
            "Language snapshot payload is outside bounded size"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == VERSION)
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
                    entityType = entityName.takeIf { it.isNotBlank() }?.let(EntityType::valueOf),
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

data class DurableLanguageRuntimeHead(
    val headRevision: Long,
    val activeSnapshotFingerprint: String,
    val activeLexiconRevision: Long,
    val predecessorSnapshotFingerprint: String?,
) {
    init {
        require(headRevision > 0L)
        require(activeSnapshotFingerprint.isNotBlank())
        require(activeLexiconRevision > 0L)
        require(predecessorSnapshotFingerprint == null || predecessorSnapshotFingerprint.isNotBlank())
    }
}

class DurableLanguageRuntimeCoordinator(
    private val runtime: VersionedLanguageRuntime,
    private val photons: RevisionedPhotonRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun rehydrate(): LanguageRuntimeSnapshot {
        val headPhoton = photons.load(HEAD_ID)
        if (headPhoton == null) return initializeBuiltin()
        val head = decodeHead(headPhoton)
        val snapshot = loadSnapshot(head.activeSnapshotFingerprint)
        require(snapshot.revision == head.activeLexiconRevision) {
            "Language runtime head/snapshot revision mismatch"
        }
        require(snapshot.predecessorFingerprint == head.predecessorSnapshotFingerprint) {
            "Language runtime head/snapshot predecessor mismatch"
        }
        return runtime.install(snapshot)
    }

    suspend fun promote(
        concepts: Collection<LinguisticConcept>,
        promotionEvidenceFingerprint: String,
    ): LanguageRuntimeSnapshot {
        val current = ensureCurrentHead()
        require(runtime.current().lexicon.fingerprint == current.activeSnapshotFingerprint) {
            "In-memory language runtime differs from durable head"
        }
        val candidate = runtime.nextSnapshot(concepts, promotionEvidenceFingerprint)
        persistSnapshot(candidate)
        val nextHead = DurableLanguageRuntimeHead(
            headRevision = current.headRevision + 1L,
            activeSnapshotFingerprint = candidate.fingerprint,
            activeLexiconRevision = candidate.revision,
            predecessorSnapshotFingerprint = candidate.predecessorFingerprint,
        )
        persistHead(nextHead, expectedPreviousRevision = current.headRevision)
        return runtime.install(candidate)
    }

    suspend fun rollback(targetSnapshotFingerprint: String): LanguageRuntimeSnapshot {
        val current = ensureCurrentHead()
        require(targetSnapshotFingerprint != current.activeSnapshotFingerprint) {
            "Language runtime rollback target is already active"
        }
        val target = loadSnapshot(targetSnapshotFingerprint)
        val nextHead = DurableLanguageRuntimeHead(
            headRevision = current.headRevision + 1L,
            activeSnapshotFingerprint = target.fingerprint,
            activeLexiconRevision = target.revision,
            predecessorSnapshotFingerprint = target.predecessorFingerprint,
        )
        persistHead(nextHead, expectedPreviousRevision = current.headRevision)
        return runtime.install(target)
    }

    suspend fun currentHead(): DurableLanguageRuntimeHead = ensureCurrentHead()

    private suspend fun initializeBuiltin(): LanguageRuntimeSnapshot {
        val builtin = runtime.current().lexicon
        persistSnapshot(builtin)
        val head = DurableLanguageRuntimeHead(
            headRevision = 1L,
            activeSnapshotFingerprint = builtin.fingerprint,
            activeLexiconRevision = builtin.revision,
            predecessorSnapshotFingerprint = builtin.predecessorFingerprint,
        )
        when (val result = photons.saveRevision(headPhoton(head, Instant.EPOCH), expectedPreviousRevision = null)) {
            is PhotonRevisionWriteResult.Created,
            is PhotonRevisionWriteResult.Idempotent -> Unit
            is PhotonRevisionWriteResult.Advanced ->
                error("Language runtime initialization unexpectedly advanced existing head")
            is PhotonRevisionWriteResult.Conflict -> {
                val durableHead = requireNotNull(photons.load(HEAD_ID))
                val winner = decodeHead(durableHead)
                return runtime.install(loadSnapshot(winner.activeSnapshotFingerprint))
            }
        }
        return runtime.install(builtin)
    }

    private suspend fun ensureCurrentHead(): DurableLanguageRuntimeHead {
        val photon = photons.load(HEAD_ID) ?: run {
            initializeBuiltin()
            requireNotNull(photons.load(HEAD_ID))
        }
        return decodeHead(photon)
    }

    private suspend fun persistSnapshot(snapshot: LinguisticLexiconSnapshot) {
        val photon = Photon(
            id = snapshotId(snapshot.fingerprint),
            revision = 1L,
            content = LinguisticLexiconSnapshotCodec.encode(snapshot),
            mimeType = SNAPSHOT_MIME,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 0.0,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "language-runtime",
                actor = "DurableLanguageRuntimeCoordinator",
                createdAt = Instant.EPOCH,
            ),
            tags = setOf(
                "language-runtime-state",
                "language-runtime-snapshot",
                "language-runtime-fingerprint:${snapshot.fingerprint}",
                "language-runtime-revision:${snapshot.revision}",
            ),
        )
        when (val result = photons.saveRevision(photon, expectedPreviousRevision = null)) {
            is PhotonRevisionWriteResult.Created,
            is PhotonRevisionWriteResult.Idempotent -> Unit
            is PhotonRevisionWriteResult.Advanced ->
                error("Immutable language snapshot unexpectedly advanced")
            is PhotonRevisionWriteResult.Conflict ->
                error("Language snapshot persistence conflict: ${result.reason}")
        }
    }

    private suspend fun loadSnapshot(fingerprint: String): LinguisticLexiconSnapshot {
        val photon = requireNotNull(photons.load(snapshotId(fingerprint))) {
            "Language runtime head points to missing snapshot: $fingerprint"
        }
        require(photon.mimeType == SNAPSHOT_MIME && "language-runtime-snapshot" in photon.tags) {
            "Language runtime snapshot identity resolved to incompatible Photon"
        }
        val snapshot = LinguisticLexiconSnapshotCodec.decode(photon.content)
        require(snapshot.fingerprint == fingerprint) { "Language runtime snapshot identity mismatch" }
        return snapshot
    }

    private suspend fun persistHead(
        head: DurableLanguageRuntimeHead,
        expectedPreviousRevision: Long,
    ) {
        when (
            val result = photons.saveRevision(
                headPhoton(head, now()),
                expectedPreviousRevision = expectedPreviousRevision,
            )
        ) {
            is PhotonRevisionWriteResult.Advanced,
            is PhotonRevisionWriteResult.Idempotent -> Unit
            is PhotonRevisionWriteResult.Created ->
                error("Language runtime promotion created a missing head instead of advancing it")
            is PhotonRevisionWriteResult.Conflict ->
                error("Language runtime head CAS conflict: ${result.reason}")
        }
    }

    private fun headPhoton(head: DurableLanguageRuntimeHead, createdAt: Instant): Photon = Photon(
        id = HEAD_ID,
        revision = head.headRevision,
        content = buildString {
            appendLine("language-runtime-head/v1")
            appendLine("head_revision=${head.headRevision}")
            appendLine("snapshot=${head.activeSnapshotFingerprint}")
            appendLine("lexicon_revision=${head.activeLexiconRevision}")
            append("predecessor=${head.predecessorSnapshotFingerprint.orEmpty()}")
        },
        mimeType = HEAD_MIME,
        phase = PhotonPhase.ACTIVE,
        semanticMass = 0.0,
        energy = 0.0,
        confidence = 1.0,
        provenance = Provenance(
            source = "language-runtime",
            actor = "DurableLanguageRuntimeCoordinator",
            createdAt = createdAt,
            parentIds = setOf(snapshotId(head.activeSnapshotFingerprint)),
        ),
        tags = setOf(
            "language-runtime-state",
            "language-runtime-head",
            "language-runtime-active:${head.activeSnapshotFingerprint}",
        ),
    )

    private fun decodeHead(photon: Photon): DurableLanguageRuntimeHead {
        require(
            photon.id == HEAD_ID &&
                photon.mimeType == HEAD_MIME &&
                "language-runtime-head" in photon.tags
        )
        val lines = photon.content.lineSequence().toList()
        require(lines.firstOrNull() == "language-runtime-head/v1")
        val values = lines.drop(1).associate { line ->
            val split = line.indexOf('=')
            require(split > 0) { "Malformed language runtime head" }
            line.substring(0, split) to line.substring(split + 1)
        }
        val head = DurableLanguageRuntimeHead(
            headRevision = requireNotNull(values["head_revision"]).toLong(),
            activeSnapshotFingerprint = requireNotNull(values["snapshot"]),
            activeLexiconRevision = requireNotNull(values["lexicon_revision"]).toLong(),
            predecessorSnapshotFingerprint = values["predecessor"]?.ifBlank { null },
        )
        require(head.headRevision == photon.revision) {
            "Language runtime head revision/path mismatch"
        }
        require(snapshotId(head.activeSnapshotFingerprint) in photon.provenance.parentIds) {
            "Language runtime head dropped active snapshot provenance"
        }
        return head
    }

    private fun snapshotId(fingerprint: String): PhotonId =
        PhotonId("language-runtime-snapshot-$fingerprint")

    private companion object {
        val HEAD_ID = PhotonId("language-runtime-head")
        const val HEAD_MIME = "application/vnd.lifeos.language-runtime-head+text"
        const val SNAPSHOT_MIME = "application/vnd.lifeos.language-runtime-snapshot+text"
    }
}
