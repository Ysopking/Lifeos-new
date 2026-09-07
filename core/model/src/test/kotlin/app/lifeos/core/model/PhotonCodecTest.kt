package app.lifeos.core.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PhotonCodecTest {
    @Test fun preservesFullPhotonIncludingGraphAndNanoseconds() {
        val parent = PhotonId.new()
        val photon = Photon(content = "Gedanke 🧠".repeat(10_000), revision = 7,
            phase = PhotonPhase.REFLECTING, semanticMass = 2.5, energy = 3.0, confidence = .8,
            provenance = Provenance("import", "user", Instant.parse("2026-09-07T12:00:00.123456789Z"), setOf(parent)),
            relations = setOf(PhotonRelation(parent, RelationType.DERIVED_FROM, .7)), tags = setOf("chat", "test"))
        assertEquals(photon, PhotonCodec.decode(PhotonCodec.encode(photon)))
    }

    @Test fun readsExistingVersionOneFiles() {
        val photon = Photon(content = "Alter Gedanke", provenance = Provenance("local-chat", "user", Instant.ofEpochMilli(1234)), tags = setOf("chat"))
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeUTF(photon.id.value); out.writeLong(1); out.writeUTF(photon.content)
            out.writeUTF("text/plain"); out.writeUTF("CREATED")
            repeat(3) { out.writeDouble(1.0) }
            out.writeUTF("local-chat"); out.writeUTF("user"); out.writeLong(1234)
            out.writeInt(1); out.writeUTF("chat")
        } }.toByteArray()
        assertEquals(photon, PhotonCodec.decode(bytes, 1))
    }

    @Test fun rejectsTruncatedTrailingAndUnsupportedPayloads() {
        val bytes = PhotonCodec.encode(Photon(content = "Test", provenance = Provenance("test", "user")))
        assertFails { PhotonCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertFails { PhotonCodec.decode(bytes + byteArrayOf(0)) }
        assertFails { PhotonCodec.decode(bytes, 99) }
        assertFails { PhotonCodec.decode(byteArrayOf(127, -1, -1, -1)) }
    }
}
