package app.lifeos.core.runtime.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class WebResourceIdentityTest {
    @Test
    fun `scheme host default port fragment and dot segments canonicalize to one identity`() {
        val first = WebResourceIdentity.parse(
            "HTTPS://Example.COM:443/a/./b/../c/%7euser?q=%7evalue#fragment"
        )
        val second = WebResourceIdentity.parse(
            "https://example.com/a/c/~user?q=~value#other"
        )

        assertEquals("https://example.com/a/c/~user?q=~value", first.canonicalUrl)
        assertEquals(first, second)
        assertEquals(first.id, second.id)
        assertEquals(null, first.fragment)
        assertEquals("https://example.com", first.origin.canonicalOrigin)
        assertFalse(first.contentAuthority)
        assertFalse(first.trustAuthority)
        assertFalse(first.networkAuthority)
        assertFalse(first.permissionAuthority)
    }

    @Test
    fun `unicode host and path canonicalize to ASCII resource identity`() {
        val identity = WebResourceIdentity.parse(
            "https://bücher.example/Überblick"
        )

        assertEquals("xn--bcher-kva.example", identity.origin.host)
        assertEquals(
            "https://xn--bcher-kva.example/%C3%9Cberblick",
            identity.canonicalUrl,
        )
    }

    @Test
    fun `empty path canonicalizes to root and root DNS dot is removed`() {
        val identity = WebResourceIdentity.parse("https://Example.COM.")

        assertEquals("/", identity.path)
        assertEquals("example.com", identity.origin.host)
        assertEquals("https://example.com/", identity.canonicalUrl)
    }

    @Test
    fun `query order and duplicates are preserved because servers may distinguish them`() {
        val first = WebResourceIdentity.parse(
            "https://example.com/search?a=1&a=2&b=3"
        )
        val reordered = WebResourceIdentity.parse(
            "https://example.com/search?b=3&a=1&a=2"
        )

        assertEquals("a=1&a=2&b=3", first.query)
        assertEquals("b=3&a=1&a=2", reordered.query)
        assertNotEquals(first.id, reordered.id)
    }

    @Test
    fun `non default port participates in origin and resource identity`() {
        val standard = WebResourceIdentity.parse("https://example.com/path")
        val alternate = WebResourceIdentity.parse("https://example.com:8443/path")

        assertEquals(null, standard.origin.port)
        assertEquals(8443, alternate.origin.port)
        assertNotEquals(standard.origin.id, alternate.origin.id)
        assertNotEquals(standard.id, alternate.id)
    }

    @Test
    fun `different paths share an origin but not a resource identity`() {
        val first = WebResourceIdentity.parse("https://example.com/a")
        val second = WebResourceIdentity.parse("https://example.com/b")

        assertEquals(first.origin, second.origin)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `fragment never participates in resource identity`() {
        val first = WebResourceIdentity.parse("https://example.com/page#one")
        val second = WebResourceIdentity.parse("https://example.com/page#two")

        assertEquals(first, second)
        assertEquals("https://example.com/page", first.canonicalUrl)
    }

    @Test
    fun `reserved percent encoding remains encoded while unreserved encoding normalizes`() {
        val identity = WebResourceIdentity.parse(
            "https://example.com/a%2fb/%41?q=%2f%41"
        )

        assertEquals("/a%2Fb/A", identity.path)
        assertEquals("q=%2FA", identity.query)
        assertEquals("https://example.com/a%2Fb/A?q=%2FA", identity.canonicalUrl)
    }

    @Test
    fun `userinfo non https missing host and invalid ports fail closed`() {
        listOf(
            "http://example.com/",
            "https://user:secret@example.com/",
            "https:///missing-host",
            "https://example.com:0/",
            "https://example.com:65536/",
            "https://example.com:not-a-port/",
        ).forEach { raw ->
            assertFailsWith<IllegalArgumentException> {
                WebResourceIdentity.parse(raw)
            }
        }
    }

    @Test
    fun `canonicalization performs no trust permission or network authorization`() {
        val identity = WebResourceIdentity.parse("https://127.0.0.1/resource")

        assertEquals("127.0.0.1", identity.origin.host)
        assertFalse(identity.origin.networkAuthority)
        assertFalse(identity.origin.permissionAuthority)
        assertFalse(identity.networkAuthority)
        assertFalse(identity.permissionAuthority)
        assertFalse(identity.trustAuthority)
    }
}
