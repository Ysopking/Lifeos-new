package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntitySystemV2Test {
    private val normalizer = UtteranceNormalizer()
    private val pipeline = DeterministicEntityPipelineV2()

    @Test
    fun `domain compounds yield modular entity types`() {
        val result = pipeline.extract(
            normalizer.normalize(
                "Jobcenter-Bescheid Widerspruchsfrist Ratenzahlungsvereinbarung"
            )
        )

        val types = result.entities.mapTo(setOf()) { it.typeId }
        assertTrue(EntityTypeRegistry.AUTHORITY.id in types)
        assertTrue(EntityTypeRegistry.NOTICE.id in types)
        assertTrue(EntityTypeRegistry.CLAIM.id in types)
        assertTrue(EntityTypeRegistry.DEADLINE.id in types)
        assertTrue(EntityTypeRegistry.INSTALLMENT.id in types)
        assertTrue(EntityTypeRegistry.CONTRACT.id in types)
    }

    @Test
    fun `structured communication and finance entities are recognized`() {
        val result = pipeline.extract(
            normalizer.normalize(
                "Sende an test@example.org 312 Euro von DE89370400440532013000."
            )
        )

        assertTrue(result.entities.any {
            it.typeId == EntityTypeRegistry.EMAIL_ADDRESS.id &&
                it.normalizedValue == "test@example.org"
        })
        assertTrue(result.entities.any {
            it.typeId == EntityTypeRegistry.MONEY.id &&
                it.normalizedValue.contains("312")
        })
        assertTrue(result.entities.any {
            it.typeId == EntityTypeRegistry.BANK_ACCOUNT.id &&
                it.normalizedValue == "DE89370400440532013000"
        })
    }

    @Test
    fun `legacy projection contains only registered compatible types`() {
        val result = pipeline.extract(normalizer.normalize("Bild in Berlin mit Peter"))

        assertTrue(result.legacyProjection.any { it.type == EntityType.IMAGE })
        assertTrue(result.legacyProjection.any { it.type == EntityType.LOCATION })
        assertTrue(result.entities.size >= result.legacyProjection.size)
        assertEquals(
            result.legacyProjection,
            result.legacyProjection.distinctBy {
                listOf(it.type, it.tokenStart, it.tokenEndExclusive, it.normalizedValue)
            },
        )
    }
}
