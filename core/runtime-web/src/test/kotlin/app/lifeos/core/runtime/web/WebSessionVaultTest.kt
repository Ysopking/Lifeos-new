package app.lifeos.core.runtime.web

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebSessionVaultTest {
    @Test
    fun secret_is_redacted_and_defensively_copied() {
        val source = "secret-cookie".encodeToByteArray()
        val secret = WebSessionSecret.fromBytes(source)
        source.fill(0)

        assertContentEquals("secret-cookie".encodeToByteArray(), secret.copyBytes())
        assertFalse(secret.toString().contains("secret-cookie"))

        val copy = secret.copyBytes()
        copy.fill(0)
        assertContentEquals("secret-cookie".encodeToByteArray(), secret.copyBytes())
    }

    @Test
    fun session_identity_is_origin_and_slot_bound_without_secret_hash_identity() {
        val origin = WebResourceIdentity.parse("https://example.com/a").origin
        val first = WebSessionSnapshot.create(
            origin = origin,
            entries = listOf(cookie("sid", "alpha")),
        )
        val changedSecretSameRevisionShape = WebSessionSnapshot.create(
            origin = origin,
            entries = listOf(cookie("sid", "beta")),
        )
        val secondSlot = WebSessionSnapshot.create(
            origin = origin,
            slot = WebSessionSlot("work"),
            entries = listOf(cookie("sid", "alpha")),
        )

        assertEquals(first.sessionId, changedSecretSameRevisionShape.sessionId)
        assertEquals(first.revisionId, changedSecretSameRevisionShape.revisionId)
        assertNotEquals(first.sessionId, secondSlot.sessionId)
        assertFalse(first.authenticationAuthority)
        assertFalse(first.permissionAuthority)
        assertFalse(first.executionAuthority)
    }

    @Test
    fun revisions_are_contiguous_and_predecessor_bound() {
        val origin = WebResourceIdentity.parse("https://example.com/").origin
        val first = WebSessionSnapshot.create(
            origin = origin,
            entries = listOf(cookie("sid", "v1")),
        )
        val second = WebSessionSnapshot.create(
            origin = origin,
            entries = listOf(cookie("sid", "v2")),
            previous = first,
        )

        assertEquals(1L, first.revision)
        assertEquals(2L, second.revision)
        assertEquals(first.revisionId, second.predecessorRevisionId)

        assertFailsWith<IllegalArgumentException> {
            second.copy(predecessorRevisionId = null)
        }
    }

    @Test
    fun entry_order_and_identity_duplicates_fail_closed() {
        val origin = WebResourceIdentity.parse("https://example.com/").origin
        val a = cookie("a", "1")
        val b = cookie("b", "2")
        val canonical = WebSessionSnapshot.create(origin, listOf(b, a))

        assertEquals(listOf("a", "b"), canonical.entries.map { it.name })

        assertFailsWith<IllegalArgumentException> {
            canonical.copy(entries = listOf(b, a))
        }
        assertFailsWith<IllegalArgumentException> {
            WebSessionSnapshot.create(origin, listOf(a, a.copy(secret = WebSessionSecret.fromUtf8("x"))))
        }
    }

    @Test
    fun codec_round_trip_preserves_secret_material_without_granting_authority() {
        val origin = WebResourceIdentity.parse("https://example.com/app").origin
        val first = WebSessionSnapshot.create(
            origin = origin,
            entries = listOf(
                WebSessionEntry(
                    kind = WebSessionEntryKind.AUTHORIZATION,
                    name = "primary",
                    secret = WebSessionSecret.fromUtf8("Bearer opaque-token"),
                    expiresAtEpochMillis = 4_000_000L,
                ),
                cookie("sid", "cookie-value"),
            ),
        )

        val restored = WebSessionCodec.decode(WebSessionCodec.encode(first))

        assertEquals(first, restored)
        assertContentEquals(
            "Bearer opaque-token".encodeToByteArray(),
            restored.entries.first { it.kind == WebSessionEntryKind.AUTHORIZATION }
                .secret.copyBytes(),
        )
        assertTrue(WebSessionCodec.encode(first).size <= WebSessionCodec.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun load_report_rejects_missing_revision_chain() {
        val origin = WebResourceIdentity.parse("https://example.com/").origin
        val first = WebSessionSnapshot.create(
            origin = origin,
            entries = listOf(cookie("sid", "v1")),
        )
        val second = WebSessionSnapshot.create(
            origin = origin,
            entries = listOf(cookie("sid", "v2")),
            previous = first,
        )
        val third = WebSessionSnapshot.create(
            origin = origin,
            entries = listOf(cookie("sid", "v3")),
            previous = second,
        )

        assertFailsWith<IllegalArgumentException> {
            WebSessionLoadReport(
                snapshots = listOf(first, third),
                unreadableEntries = emptyList(),
            )
        }
    }

    @Test
    fun codec_rejects_trailing_bytes_and_cross_origin_predecessor() {
        val one = WebSessionSnapshot.create(
            WebResourceIdentity.parse("https://one.example/").origin,
            listOf(cookie("sid", "1")),
        )
        val encoded = WebSessionCodec.encode(one)

        assertFailsWith<IllegalArgumentException> {
            WebSessionCodec.decode(encoded + byteArrayOf(1))
        }

        val twoOrigin = WebResourceIdentity.parse("https://two.example/").origin
        assertFailsWith<IllegalArgumentException> {
            WebSessionSnapshot.create(
                origin = twoOrigin,
                entries = listOf(cookie("sid", "2")),
                previous = one,
            )
        }
    }

    private fun cookie(name: String, value: String): WebSessionEntry =
        WebSessionEntry(
            kind = WebSessionEntryKind.COOKIE,
            name = name,
            secret = WebSessionSecret.fromUtf8(value),
        )
}
