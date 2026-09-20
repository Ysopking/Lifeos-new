package app.lifeos.next

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.personal.PersonalConversationCorpusImporter
import app.lifeos.core.runtime.personal.PersonalConversationSpeaker
import java.io.File
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalConversationImportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun previewIsReadOnlyThenImportIsIdempotentAndRoleSafe() = runBlocking {
        val file = File(context.cacheDir, "gemini-personal-import-test.json")
        file.writeText(
            """
            [
              {
                "id": "conversation-a",
                "createdAt": "2026-09-20T08:00:00Z",
                "prompt": "zieh das komplett durch",
                "response": "Alles klar."
              },
              {
                "id": "conversation-b",
                "messages": [
                  {"id": "u1", "role": "user", "content": "mach weiter"},
                  {"id": "a1", "role": "model", "content": "Ich mache weiter."},
                  {"id": "s1", "role": "system", "content": "interne Metadaten"}
                ]
              }
            ]
            """.trimIndent()
        )

        val repository = InMemoryRevisionedPhotonRepository()
        val runtime = AndroidPersonalConversationImportRuntime(
            context = context,
            importer = PersonalConversationCorpusImporter(repository),
            zoneId = ZoneId.of("Europe/Berlin"),
        )

        val preview = runtime.preview(
            kind = PersonalConversationImportKind.GEMINI,
            uri = Uri.fromFile(file),
        )

        assertEquals(0, repository.loadAll().size)
        assertEquals(4, preview.turnCount)
        assertEquals(2, preview.ownerTurns)
        assertEquals(2, preview.assistantTurns)
        assertEquals(0, preview.otherTurns)
        assertEquals(1, preview.skippedUnknownRoles)

        val first = runtime.import(preview)
        val second = runtime.import(preview)

        assertEquals(4, first.created)
        assertEquals(0, first.replayed)
        assertEquals(0, second.created)
        assertEquals(4, second.replayed)
        assertEquals(4, repository.loadAll().size)
        assertEquals(
            2,
            repository.loadAll().count { "speaker:owner" in it.tags },
        )
        assertEquals(
            2,
            repository.loadAll().count { "speaker:assistant" in it.tags },
        )
        assertTrue(repository.loadAll().none { "speaker:unknown" in it.tags })
        file.delete()
        Unit
    }

    @Test
    fun unknownGeminiSchemaFailsClosedBeforeAnyWrite() = runBlocking {
        val file = File(context.cacheDir, "gemini-unknown-schema.json")
        file.writeText("""{"metadata":{"title":"not a recognized conversation schema"}}""")
        val repository = InMemoryRevisionedPhotonRepository()
        val runtime = AndroidPersonalConversationImportRuntime(
            context = context,
            importer = PersonalConversationCorpusImporter(repository),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                runtime.preview(
                    kind = PersonalConversationImportKind.GEMINI,
                    uri = Uri.fromFile(file),
                )
            }
        }
        assertTrue(repository.loadAll().isEmpty())
        file.delete()
        Unit
    }

    @Test
    fun whatsappZipImportsOnlyChatTextAndPreservesOwnerBoundary() = runBlocking {
        val file = File(context.cacheDir, "whatsapp-personal-import-test.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("_chat.txt"))
            zip.write(
                (
                    "[20.09.26, 10:01] Tava: Mach bitte weiter\n" +
                        "[20.09.26, 10:02] Alex: Mache ich.\n"
                    ).encodeToByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("IMG-0001.jpg"))
            zip.write(byteArrayOf(1, 2, 3, 4))
            zip.closeEntry()
        }

        val repository = InMemoryRevisionedPhotonRepository()
        val runtime = AndroidPersonalConversationImportRuntime(
            context = context,
            importer = PersonalConversationCorpusImporter(repository),
            zoneId = ZoneId.of("Europe/Berlin"),
        )
        val preview = runtime.preview(
            kind = PersonalConversationImportKind.WHATSAPP,
            uri = Uri.fromFile(file),
            ownerNames = setOf("Tava"),
        )

        assertEquals(2, preview.turnCount)
        assertEquals(1, preview.ownerTurns)
        assertEquals(1, preview.otherTurns)
        assertEquals(1, preview.archiveEntries)
        assertTrue(repository.loadAll().isEmpty())

        val imported = runtime.import(
            preview = preview,
            ownerNames = setOf("Tava"),
        )

        assertEquals(2, imported.created)
        assertEquals(1, imported.speakerCounts[PersonalConversationSpeaker.OWNER])
        assertEquals(1, imported.speakerCounts[PersonalConversationSpeaker.OTHER])
        assertTrue(repository.loadAll().all { it.mimeType.contains("personal-conversation") })
        file.delete()
        Unit
    }

    private class InMemoryRevisionedPhotonRepository : RevisionedPhotonRepository {
        private val photons = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            photons[photon.id] = photon
        }

        override suspend fun loadAll(): List<Photon> = photons.values.toList()

        override suspend fun delete(id: PhotonId) {
            photons.remove(id)
        }

        override suspend fun load(id: PhotonId): Photon? = photons[id]

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(
                photons = photons.values.toList(),
                unreadableFiles = emptyList(),
            )

        override suspend fun load(ref: PhotonRevisionRef): Photon? =
            photons[ref.photonId]?.takeIf { it.revision == ref.revision }

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            photons[id]?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val previous = photons[photon.id]
            if (previous == null) {
                if (expectedPreviousRevision != null) {
                    return PhotonRevisionWriteResult.Conflict(
                        photon = photon,
                        previous = null,
                        reason = "missing-previous",
                    )
                }
                photons[photon.id] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (previous == photon) {
                return PhotonRevisionWriteResult.Idempotent(
                    photon = photon,
                    previous = previous,
                )
            }
            return PhotonRevisionWriteResult.Conflict(
                photon = photon,
                previous = previous,
                reason = "immutable-test-repository",
            )
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val selected = photons.values.asSequence().filter { photon ->
                (query.ids.isEmpty() || photon.id in query.ids) &&
                    (query.phases.isEmpty() || photon.phase in query.phases) &&
                    (query.mimeTypes.isEmpty() || photon.mimeType in query.mimeTypes) &&
                    photon.tags.containsAll(query.allTags) &&
                    photon.tags.none { it in query.excludedTags }
            }
            val sorted = when (query.order) {
                PhotonIndexOrder.NEWEST_FIRST -> selected.sortedByDescending { it.provenance.createdAt }
                PhotonIndexOrder.OLDEST_FIRST -> selected.sortedBy { it.provenance.createdAt }
                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS -> selected.sortedByDescending { it.semanticMass }
                PhotonIndexOrder.HIGHEST_CONFIDENCE -> selected.sortedByDescending { it.confidence }
                PhotonIndexOrder.IDENTITY -> selected.sortedBy { it.id.value }
            }
            return sorted.take(query.limit).map {
                PhotonRevisionRef(it.id, it.revision)
            }.toList()
        }

        override suspend fun indexReport(): PhotonIndexReport =
            PhotonIndexReport(
                formatVersion = 1,
                entryCount = photons.size,
                livePhotonCount = photons.size,
                tombstonedPhotonCount = 0,
                latestRefs = photons.values.associate { photon ->
                    photon.id to PhotonRevisionRef(photon.id, photon.revision)
                },
            )
    }
}
