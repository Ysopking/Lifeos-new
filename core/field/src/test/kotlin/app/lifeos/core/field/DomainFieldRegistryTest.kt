package app.lifeos.core.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DomainFieldRegistryTest {
    private val domain = StableFieldIds.domain("registry.test")

    @Test
    fun `registry orders fields deterministically`() {
        val low = field("beta", version = 1, priority = 1)
        val high = field("alpha", version = 2, priority = 10)
        val registry = DomainFieldRegistry(listOf(low, high))

        assertEquals(listOf(high, low), registry.fieldsFor(domain))
        assertEquals(registry.fieldsFor(domain), DomainFieldRegistry(listOf(high, low)).fieldsFor(domain))
    }

    @Test
    fun `canonical duplicate descriptor keys are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DomainFieldRegistry(listOf(field("Meaning", 1), field(" meaning ", 1)))
        }
    }

    @Test
    fun `field version participates in registry fingerprint`() {
        val first = DomainFieldRegistry(listOf(field("meaning", 1))).fingerprint()
        val second = DomainFieldRegistry(listOf(field("meaning", 2))).fingerprint()

        assertNotEquals(first, second)
    }

    private fun field(name: String, version: Int, priority: Int = 0): DomainField = object : DomainField {
        override val descriptor = DomainFieldDescriptor(
            domainId = domain,
            name = name,
            version = version,
            priority = priority,
        )

        override fun seed(evidence: List<FieldEvidence>, context: FieldContext): DomainFieldSeed =
            error("Registry test does not execute field seeding")
    }
}
