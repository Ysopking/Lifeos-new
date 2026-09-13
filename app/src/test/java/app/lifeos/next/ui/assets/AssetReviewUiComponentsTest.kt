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

        assertEquals("Offen 0", filterLabel(AssetReviewFilter.PENDING, state))
        assertEquals("Freigegeben 0", filterLabel(AssetReviewFilter.APPROVED, state))
        assertEquals("Rückmeldung 0", filterLabel(AssetReviewFilter.FEEDBACK, state))
    }

    @Test
    fun `empty states explain the selected review bucket`() {
        assertEquals("Alles geprüft", emptyTitle(AssetReviewFilter.PENDING))
        assertEquals("Noch nichts freigegeben", emptyTitle(AssetReviewFilter.APPROVED))
        assertEquals("Noch keine Rückmeldungen", emptyTitle(AssetReviewFilter.FEEDBACK))
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
