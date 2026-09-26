package app.lifeos.next.ui.assets

import app.lifeos.core.runtime.artifact.ArtifactKind
import app.lifeos.next.AssetReviewFilter
import app.lifeos.next.OwnerAssetReviewUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetReviewUiComponentsTest {
    @Test
    fun `overview labels use owner facing language`() {
        val state = OwnerAssetReviewUiState()

        assertEquals("Neu 0", filterLabel(AssetReviewFilter.PENDING, state))
        assertEquals("Fertig 0", filterLabel(AssetReviewFilter.APPROVED, state))
        assertEquals("Überarbeiten 0", filterLabel(AssetReviewFilter.FEEDBACK, state))
    }

    @Test
    fun `empty states explain the selected review bucket`() {
        assertEquals("Nichts Neues", emptyTitle(AssetReviewFilter.PENDING))
        assertEquals("Noch kein fertiges Artefakt", emptyTitle(AssetReviewFilter.APPROVED))
        assertEquals("Nichts zu überarbeiten", emptyTitle(AssetReviewFilter.FEEDBACK))
    }

    @Test
    fun `artifact kinds are translated for the private owner`() {
        assertEquals("Dokument", assetKindLabel(ArtifactKind.DOCUMENT))
        assertEquals("Bild", assetKindLabel(ArtifactKind.IMAGE))
        assertEquals("Code", assetKindLabel(ArtifactKind.CODE))
        assertEquals("Bericht", assetKindLabel(ArtifactKind.REPORT))
        assertEquals("Asset", assetKindLabel(ArtifactKind.OTHER))
    }
}
