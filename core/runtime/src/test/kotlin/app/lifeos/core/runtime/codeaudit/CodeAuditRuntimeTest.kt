package app.lifeos.core.runtime.codeaudit

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.informationasset.code.CodeAuditFinding
import app.lifeos.core.runtime.informationasset.code.CodeAuditSeverity
import app.lifeos.core.runtime.informationasset.code.CodeLocation
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
    fun `source commit is part of repository snapshot identity`() {
        val source = source("a.kt", "fun a() = Unit", "source-a", 1)
        val first = RepositoryCodeSnapshot.create("commit-a", listOf(source))
        val second = RepositoryCodeSnapshot.create("commit-b", listOf(source))
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `direct PhotonRepository save produces finding and retains exact source revision`() {
        val source = source(
            "Unsafe.kt",
            "class Unsafe(private val photons: PhotonRepository) { suspend fun write(photon: Photon) { photons.save(photon) } }",
            "unsafe-source",
            3,
        )
        val run = CodeAuditCoordinator().audit(
            "code-audit-test", "unsafe", "Unsafe audit", "commit-unsafe", listOf(source), observedAt.plusSeconds(1)
        )
        assertTrue(run.findings.any { it.key == "photon-ingress-bypass" })
        assertEquals(3L, run.revision.manifest.sourcePhotons.single().revision)
        assertEquals(source.photon.id, run.revision.manifest.sourcePhotons.single().photonId)
    }

    @Test
    fun `source revision change changes snapshot and InformationAsset revision`() {
        val v1 = source("Safe.kt", "fun safe() = Unit", "same-source", 1)
        val v2 = source("Safe.kt", "fun safe() = Unit", "same-source", 2)
        val coordinator = CodeAuditCoordinator()
        val first = coordinator.audit(
            "code-audit-test", "revision-change", "Revision audit", "commit-1", listOf(v1), observedAt.plusSeconds(1)
        )
        val second = coordinator.audit(
            "code-audit-test", "revision-change", "Revision audit", "commit-1", listOf(v2), observedAt.plusSeconds(1)
        )
        assertNotEquals(first.snapshot.fingerprint, second.snapshot.fingerprint)
        assertNotEquals(first.revision.manifest.id, second.revision.manifest.id)
    }

    @Test
    fun `source commit change changes InformationAsset revision even for identical source photons`() {
        val source = source("Safe.kt", "fun safe() = Unit", "same-source", 1)
        val coordinator = CodeAuditCoordinator()
        val first = coordinator.audit(
            "code-audit-test", "commit-change", "Commit audit", "commit-a", listOf(source), observedAt.plusSeconds(1)
        )
        val second = coordinator.audit(
            "code-audit-test", "commit-change", "Commit audit", "commit-b", listOf(source), observedAt.plusSeconds(1)
        )
        assertNotEquals(first.snapshot.fingerprint, second.snapshot.fingerprint)
        assertNotEquals(first.revision.manifest.id, second.revision.manifest.id)
    }

    @Test
    fun `canonical ingress source is not reported as direct persistence bypass`() {
        val source = source(
            "Safe.kt",
            "class Safe(private val ingress: CanonicalPhotonIngress) { suspend fun write(photon: Photon) { ingress.ingest(photon) } }",
            "safe-source",
            1,
        )
        val run = CodeAuditCoordinator().audit(
            "code-audit-test", "safe", "Safe audit", "commit-safe", listOf(source), observedAt.plusSeconds(1)
        )
        assertTrue(run.findings.none { it.key == "photon-ingress-bypass" })
        assertTrue(run.validation.isValid)
    }

    @Test
    fun `findings are deterministically ordered regardless of rule order`() {
        val low = CodeAuditFinding(
            key = "low",
            location = CodeLocation("B.kt", 2, 2),
            severity = CodeAuditSeverity.LOW,
            summary = "Low finding",
            recommendation = "Inspect B",
        )
        val high = CodeAuditFinding(
            key = "high",
            location = CodeLocation("A.kt", 1, 1),
            severity = CodeAuditSeverity.HIGH,
            summary = "High finding",
            recommendation = "Inspect A",
        )
        val source = source("Safe.kt", "fun safe() = Unit", "ordering-source", 1)
        val first = CodeAuditCoordinator(
            rules = listOf(CodeAuditRule { listOf(low) }, CodeAuditRule { listOf(high) }),
        ).audit(
            "code-audit-test", "ordering-a", "Ordering audit", "commit-order", listOf(source), observedAt.plusSeconds(1)
        )
        val second = CodeAuditCoordinator(
            rules = listOf(CodeAuditRule { listOf(high) }, CodeAuditRule { listOf(low) }),
        ).audit(
            "code-audit-test", "ordering-b", "Ordering audit", "commit-order", listOf(source), observedAt.plusSeconds(1)
        )
        assertEquals(listOf("high", "low"), first.findings.map { it.key })
        assertEquals(first.findings, second.findings)
    }

    private fun source(path: String, content: String, id: String, revision: Long) = CodeAuditSourceFile(
        path = path,
        photon = Photon(
            id = PhotonId(id),
            revision = revision,
            content = content,
            mimeType = "text/x-kotlin",
            provenance = Provenance(source = "code-audit-test", actor = "test", createdAt = observedAt),
        ),
    )
}
