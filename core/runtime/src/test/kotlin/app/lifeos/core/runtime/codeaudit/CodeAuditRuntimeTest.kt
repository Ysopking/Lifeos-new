package app.lifeos.core.runtime.codeaudit

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CodeAuditRuntimeTest {
    private val observedAt = Instant.parse("2026-09-15T18:30:00Z")

    @Test
    fun `repository snapshot is deterministic independent of source input order`() {
        val first = source("a.kt", "fun a() = Unit", "source-a", 1)
        val second = source("b.kt", "fun b() = Unit", "source-b", 1)

        val left = RepositoryCodeSnapshot.create("abc123", listOf(first, second))
        val right = RepositoryCodeSnapshot.create("abc123", listOf(second, first))

        assertEquals(left.fingerprint, right.fingerprint)
        assertEquals(listOf("a.kt", "b.kt"), left.sourceFiles.map { it.path })
    }

    @Test
    fun `direct PhotonRepository save produces high finding and retains exact source revision`() {
        val code = """
            class Unsafe(private val photons: PhotonRepository) {
                suspend fun write(photon: Photon) {
                    photons.save(photon)
                }
            }
        """.trimIndent()
        val source = source("Unsafe.kt", code, "unsafe-source", 3)

        val run = CodeAuditCoordinator().audit(
            namespace = "code-audit-test",
            stableKey = "unsafe",
            title = "Unsafe audit",
            sourceCommit = "commit-unsafe",
            sourceFiles = listOf(source),
            evaluatedAt = observedAt.plusSeconds(1),
        )

        assertTrue(run.validation.isValid)
        assertTrue(run.findings.any { it.key == "photon-ingress-bypass" })
        assertEquals(3L, run.revision.manifest.sourcePhotons.single().revision)
        assertEquals(source.photon.id, run.revision.manifest.sourcePhotons.single().photonId)
    }

    @Test
    fun `changing exact source Photon revision changes snapshot and InformationAsset revision`() {
        val v1 = source("Safe.kt", "fun safe() = Unit", "same-source", 1)
        val v2 = source("Safe.kt", "fun safe() = Unit", "same-source", 2)
        val coordinator = CodeAuditCoordinator()

        val first = coordinator.audit(
            namespace = "code-audit-test",
            stableKey = "revision-change",
            title = "Revision audit",
            sourceCommit = "commit-1",
            sourceFiles = listOf(v1),
            evaluatedAt = observedAt.plusSeconds(1),
        )
        val second = coordinator.audit(
            namespace = "code-audit-test",
            stableKey = "revision-change",
            title = "Revision audit",
            sourceCommit = "commit-1",
            sourceFiles = listOf(v2),
            evaluatedAt = observedAt.plusSeconds(1),
        )

        assertNotEquals(first.snapshot.fingerprint, second.snapshot.fingerprint)
        assertNotEquals(first.revision.manifest.id, second.revision.manifest.id)
    }

    @Test
    fun `source using canonical ingress is not reported as direct persistence bypass`() {
        val code = """
            class Safe(private val ingress: CanonicalPhotonIngress) {
                suspend fun write(photon: Photon) {
                    ingress.ingest(photon)
                }
            }
        """.trimIndent()
        val source = source("Safe.kt", code, "safe-source", 1)

        val run = CodeAuditCoordinator().audit(
            namespace = "code-audit-test",
            stableKey = "safe",
            title = "Safe audit",
            sourceCommit = "commit-safe",
            sourceFiles = listOf(source),
            evaluatedAt = observedAt.plusSeconds(1),
        )

        assertTrue(run.findings.none { it.key == "photon-ingress-bypass" })
        assertTrue(run.validation.isValid)
    }

    private fun source(path: String, content: String, id: String, revision: Long): CodeAuditSourceFile =
        CodeAuditSourceFile(
            path = path,
            photon = Photon(
                id = PhotonId(id),
                revision = revision,
                content = content,
                mimeType = "text/x-kotlin",
                provenance = Provenance(
                    source = "code-audit-test",
                    actor = "test",
                    createdAt = observedAt,
                ),
            ),
        )
}
