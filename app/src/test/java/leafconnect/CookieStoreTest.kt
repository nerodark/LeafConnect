package leafconnect

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie semantics that the old HttpURLConnection code got wrong: the store
 * keeps the FULL cookie set (sticky-routing cookies included), and a cookie is
 * sent only when host, path and Secure rules allow it. These tests cover the
 * pure pieces — a StoredCookie entry, its OkHttp form, expiry, and matching.
 */
class CookieStoreTest {

    private fun stored(
        name: String,
        value: String,
        domain: String,
        path: String = "/",
        expiresAt: Long = CookieStore.NO_EXPIRY,
        secure: Boolean = false,
        httpOnly: Boolean = false,
        hostOnly: Boolean = false,
    ) = CookieStore.StoredCookie(name, value, domain, path, expiresAt, secure, httpOnly, hostOnly)

    @Test
    fun sessionCookieRoundTripsToOkHttp() {
        val c = stored("JSESSIONID", "abc123", "icm.infinitiusa.com", "/NissanLeafJanus", httpOnly = true)
        val cookie = c.toCookie()
        assertNotNull("a valid entry must build an OkHttp cookie", cookie)
        assertEquals("JSESSIONID", cookie!!.name)
        assertEquals("abc123", cookie.value)
        assertEquals("icm.infinitiusa.com", cookie.domain)
        assertEquals("/NissanLeafJanus", cookie.path)
        assertTrue(cookie.httpOnly)
        // No expiry => session cookie, exactly like the server's JSESSIONID.
        assertFalse(cookie.persistent)
    }

    @Test
    fun persistentCookieKeepsItsExpiry() {
        val expiry = 1_893_456_000_000L
        val cookie = stored("AWSALBCORS", "sticky", "icm.infinitiusa.com", "/", expiresAt = expiry).toCookie()!!
        assertTrue(cookie.persistent)
        assertEquals(expiry, cookie.expiresAt)
    }

    @Test
    fun hostOnlyCookieIsSentOnlyToItsExactHost() {
        val cookie = stored("AWSALB", "sticky", "icm.infinitiusa.com", "/", hostOnly = true).toCookie()!!
        assertTrue(cookie.hostOnly)
        assertTrue(cookie.matches("https://icm.infinitiusa.com/NissanLeafJanus/rest/x".toHttpUrl()))
        // A subdomain must NOT receive a host-only cookie.
        assertFalse(cookie.matches("https://leaf.icm.infinitiusa.com/NissanLeafJanus/rest/x".toHttpUrl()))
    }

    @Test
    fun domainCookieMatchesSubdomains() {
        val cookie = stored("JSESSIONID", "v", "infinitiusa.com", "/").toCookie()!!
        assertTrue(cookie.matches("https://icm.infinitiusa.com/NissanLeafJanus/rest".toHttpUrl()))
        assertTrue(cookie.matches("https://leaf.icm.infinitiusa.com/x".toHttpUrl()))
        assertFalse(cookie.matches("https://example.com/x".toHttpUrl()))
    }

    @Test
    fun cookiePathAppliesToDeeperPathsButNotToSiblings() {
        val cookie = stored("JSESSIONID", "v", "icm.infinitiusa.com", "/NissanLeafJanus/rest").toCookie()!!
        assertTrue(cookie.matches("https://icm.infinitiusa.com/NissanLeafJanus/rest/auth/softLoginforAAS".toHttpUrl()))
        // "/NissanLeafJanus/restx" merely shares the prefix — the cookie must
        // not be sent there (RFC 6265 path boundary).
        assertFalse(cookie.matches("https://icm.infinitiusa.com/NissanLeafJanus/restx".toHttpUrl()))
    }

    @Test
    fun secureCookieIsNeverSentOverPlainHttp() {
        val cookie = stored("JSESSIONID", "v", "icm.infinitiusa.com", "/", secure = true).toCookie()!!
        assertFalse(cookie.matches("http://icm.infinitiusa.com/NissanLeafJanus/rest".toHttpUrl()))
        assertTrue(cookie.matches("https://icm.infinitiusa.com/NissanLeafJanus/rest".toHttpUrl()))
    }

    @Test
    fun expiryDecidesLiveness() {
        val now = 1_700_000_000_000L
        // Session cookie (no expiry) is always live.
        assertTrue(stored("a", "v", "host", "/").isLive(now))
        assertTrue(stored("b", "v", "host", "/", expiresAt = now + 60_000).isLive(now))
        // An expiry in the past is dead — and doubles as a delete instruction.
        assertFalse(stored("c", "v", "host", "/", expiresAt = now - 1).isLive(now))
    }

    @Test
    fun invalidEntryBuildsToNullInsteadOfThrowing() {
        // OkHttp rejects a domain containing a slash; toCookie() must return
        // null so loadForRequest() skips it, not crash a request.
        val bad = stored("x", "v", "icm.infinitiusa.com/evil", "/").toCookie()
        assertEquals(null, bad)
    }
}
