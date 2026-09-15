package app.lifeos.core.runtime.informationasset

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandardInformationAssetValidationTest {
    @Test
    fun `standard registry exposes every specialized InformationAsset kind exactly once`() {
        val expected = setOf(
            InformationAssetKind.LEGAL,
            InformationAssetKind.ORGANIZATION,
            InformationAssetKind.FINANCIAL,
            InformationAssetKind.SCIENTIFIC,
            InformationAssetKind.CODE_AUDIT,
            InformationAssetKind.CODE_CHANGE_PROPOSAL,
            InformationAssetKind.PROJECT,
        )

        assertEquals(expected, StandardInformationAssetValidation.profiledKinds)
        assertTrue(StandardInformationAssetValidation.profiles.values.all { it.size == 1 })
        assertEquals(
            StandardInformationAssetValidation.profiles.values.flatten().map { it.id }.toSet().size,
            StandardInformationAssetValidation.profiles.size,
        )
    }

    @Test
    fun `generic kinds remain on the common validator instead of receiving invented profiles`() {
        assertFalse(InformationAssetKind.KNOWLEDGE in StandardInformationAssetValidation.profiledKinds)
        assertFalse(InformationAssetKind.OTHER in StandardInformationAssetValidation.profiledKinds)
    }

    @Test
    fun `registry creates fresh common validator instances`() {
        assertTrue(StandardInformationAssetValidation.validator() !== StandardInformationAssetValidation.validator())
    }
}
