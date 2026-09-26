package app.lifeos.core.runtime.reasoning

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetaTheoryMemoryProjectorTest {
    private val at = Instant.parse("2026-09-26T04:00:00Z")

    @Test
    fun typedProjectionKeepsSourceTruthImmutableAndMaterializesLineage() {
        val parent = PhotonId("parent-memory")
        val source = Photon(
            id = PhotonId("document-memory"),
            revision = 2,
            content = "content_excerpt=hello world",
            mimeType = "application/vnd.lifeos.file-evidence+text",
            confidence = 0.9,
            provenance = Provenance(
                source = "life-source:android-shared-files",
                actor = "android-shared-files/v3",
                createdAt = at,
                parentIds = setOf(parent),
            ),
            tags = setOf(
                "file",
                "document",
                "extension:txt",
                "file-decode:decoded",
            ),
        )

        val result = MetaTheoryMemoryProjector().project(source)

        assertEquals(MetaDomainFamily.DOCUMENT, result.descriptor.domain)
        assertTrue(result.descriptor.transitionFingerprint != null)
        assertTrue(result.descriptor.deformationFingerprint != null)
        assertFalse(result.descriptor.truthAuthority)
        assertFalse(result.descriptor.mergeAuthority)
        assertTrue(source.relations.isEmpty())
        assertTrue(
            result.projection.photon.relations.any {
                it.target == parent && it.type == RelationType.DERIVED_FROM
            }
        )
        assertTrue(
            result.projection.photon.tags.any {
                it.startsWith("metatheory:domain:document")
            }
        )
    }

    @Test
    fun mediaDomainWinsOverCrossDomainDocumentTag() {
        val source = Photon(
            id = PhotonId("media-item"),
            content = "name=photo.jpg",
            mimeType = "application/vnd.lifeos.android-media-metadata+text",
            provenance = Provenance("life-source:android-media-images", "android", at),
            tags = setOf("media", "media:image", "document", "document:photo.jpg"),
        )

        val descriptor = MetaTheoryMemoryProjector().project(source).descriptor

        assertEquals(MetaDomainFamily.MEDIA, descriptor.domain)
    }

    @Test
    fun optionalTransitionAndDeformationRemainUndefinedWhenNotSupported() {
        val source = Photon(
            id = PhotonId("standalone"),
            content = "standalone observation",
            provenance = Provenance("test", "owner", at),
        )

        val descriptor = MetaTheoryMemoryProjector().project(source).descriptor

        assertNull(descriptor.transitionFingerprint)
        assertNull(descriptor.deformationFingerprint)
        assertTrue(descriptor.observableFingerprints.isNotEmpty())
        assertTrue(descriptor.invariantFingerprints.isNotEmpty())
    }

    @Test
    fun exactSemanticCandidateCanCompareRepresentationsWithoutMergingThem() {
        val projector = MetaTheoryMemoryProjector()
        fun photon(id: String, content: String) = Photon(
            id = PhotonId(id),
            content = content,
            mimeType = "text/plain",
            provenance = Provenance("test", "owner", at),
            tags = setOf("document"),
        )

        val first = projector.project(photon("a", "same"))
        val second = projector.project(photon("b", "same"))
        val changed = projector.project(photon("c", "different"))

        assertEquals(
            first.descriptor.equivalenceCandidateFingerprint,
            second.descriptor.equivalenceCandidateFingerprint,
        )
        assertNotEquals(
            first.descriptor.equivalenceCandidateFingerprint,
            changed.descriptor.equivalenceCandidateFingerprint,
        )
        assertFalse(first.descriptor.mergeAuthority)
        assertNotEquals(first.projection.photon.id, second.projection.photon.id)
    }
}
