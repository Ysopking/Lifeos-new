package app.lifeos.core.runtime.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MetaObservationSignatureTest {
    @Test
    fun canonicalOrderMakesInputOrderIrrelevant() {
        val a = MetaObservableCoordinate.create(
            "mime",
            MetaObservableRole.OBSERVABLE,
            "a",
        )
        val b = MetaObservableCoordinate.create(
            "source",
            MetaObservableRole.CHANNEL,
            "b",
        )

        val first = MetaObservationSignature.create(
            MetaDomainFamily.DOCUMENT,
            listOf(a, b),
        )
        val second = MetaObservationSignature.create(
            MetaDomainFamily.DOCUMENT,
            listOf(b, a, a),
        )

        assertEquals(first, second)
        assertEquals(listOf("a"), first.values(MetaObservableRole.OBSERVABLE))
        assertEquals(listOf("b"), first.values(MetaObservableRole.CHANNEL))
    }

    @Test
    fun domainParticipatesInIdentity() {
        val coordinate = MetaObservableCoordinate.create(
            "mime",
            MetaObservableRole.OBSERVABLE,
            "same",
        )

        val document = MetaObservationSignature.create(
            MetaDomainFamily.DOCUMENT,
            listOf(coordinate),
        )
        val media = MetaObservationSignature.create(
            MetaDomainFamily.MEDIA,
            listOf(coordinate),
        )

        assertNotEquals(document.fingerprint, media.fingerprint)
    }
}
