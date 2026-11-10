package leafconnect

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cookie semantics the old HttpURLConnection code got wrong: the store keeps
 * the FULL cookie set (sticky-routing cookies included) and merges per
 * (domain, path, name), and a cookie is sent only when host, path and Secure
 * rules allow it. The parsing here is the app's own (Ktor hands raw headers);
 * the rules are OkHttp's / RFC 6265.
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
    fun parsesSessionCookieWithoutDomain() {
        val cookie = CookieStore.parse("JSESSIONID=abc123; Path=/NissanLeafJanus; HttpOnly", "icm.infinitiusa.com")
        assertNotNull(cookie)
        assertEquals("JSESSIONID", cookie.name)
        assertEquals("abc123", cookie.value)
        assertEquals("/NissanLeafJanus", cookie.path)
        assertTrue(cookie.httpOnly)
        // No Domain attribute => host-only cookie bound to the request host.
        assertTrue(cookie.hostOnly)
        assertEquals("icm.infinitiusa.com", cookie.domain)
        assertEquals(CookieStore.NO_EXPIRY, cookie.expiresAt)
    }

    @Test
    fun parsesExpiresWithEmbeddedCommaAsOneDate() {
        // The comma inside the date must never split the header.
        val cookie = CookieStore.parse(
            "AWSALBCORS=sticky; Domain=infinitiusa.com; Expires=Tue, 09 Sep 2025 12:00:00 GMT; Path=/; Secure",
            "icm.infinitiusa.com",
        )
        assertNotNull(cookie)
        assertEquals("infinitiusa.com", cookie.domain)
        assertFalse(cookie.hostOnly)
        assertTrue(cookie.secure)
        assertEquals(1_757_419_200_000L, cookie.expiresAt)
    }

    @Test
    fun malformedHeaderIsSkippedInsteadOfThrowing() {
        assertNull(CookieStore.parse("garbage", "icm.infinitiusa.com"))
        assertNull(CookieStore.parse("=novalue", "icm.infinitiusa.com"))
    }

    @Test
    fun hostOnlyCookieIsSentOnlyToItsExactHost() {
        val cookie = stored("AWSALB", "sticky", "icm.infinitiusa.com", "/", hostOnly = true)
        assertTrue(CookieStore.matches(cookie, "icm.infinitiusa.com", "/NissanLeafJanus/rest/x", true))
        // A subdomain must NOT receive a host-only cookie.
        assertFalse(CookieStore.matches(cookie, "leaf.icm.infinitiusa.com", "/x", true))
    }

    @Test
    fun domainCookieMatchesSubdomains() {
        val cookie = stored("JSESSIONID", "v", "infinitiusa.com", "/")
        assertTrue(CookieStore.matches(cookie, "icm.infinitiusa.com", "/NissanLeafJanus/rest", true))
        assertTrue(CookieStore.matches(cookie, "leaf.icm.infinitiusa.com", "/x", true))
        assertFalse(CookieStore.matches(cookie, "example.com", "/x", true))
    }

    @Test
    fun cookiePathAppliesToDeeperPathsButNotToSiblings() {
        val cookie = stored("JSESSIONID", "v", "icm.infinitiusa.com", "/NissanLeafJanus/rest")
        assertTrue(CookieStore.matches(cookie, "icm.infinitiusa.com", "/NissanLeafJanus/rest/auth/softLoginforAAS", true))
        // "/NissanLeafJanus/restx" merely shares the prefix — the cookie must
        // not be sent there (RFC 6265 path boundary).
        assertFalse(CookieStore.matches(cookie, "icm.infinitiusa.com", "/NissanLeafJanus/restx", true))
    }

    @Test
    fun secureCookieIsNeverSentOverPlainHttp() {
        val cookie = stored("JSESSIONID", "v", "icm.infinitiusa.com", "/", secure = true)
        assertFalse(CookieStore.matches(cookie, "icm.infinitiusa.com", "/NissanLeafJanus/rest", false))
        assertTrue(CookieStore.matches(cookie, "icm.infinitiusa.com", "/NissanLeafJanus/rest", true))
    }

    @Test
    fun expiryDecidesLiveness() {
        val now = 1_700_000_000_000L
        assertTrue(stored("a", "v", "host", "/").isLive(now))
        assertTrue(stored("b", "v", "host", "/", expiresAt = now + 60_000).isLive(now))
        // An expiry in the past is dead — and doubles as a delete instruction.
        assertFalse(stored("c", "v", "host", "/", expiresAt = now - 1).isLive(now))
    }

    @Test
    fun storeMergesCookiesAndPersistsAcrossRestarts() {
        val prefs = FakeSettingsStore()
        val store = CookieStore(prefs)
        val requestUrl = Url("https://icm.infinitiusa.com/NissanLeafJanus/rest/auth/x")
        store.saveFromResponse(listOf(
            "AWSALB=sticky; Domain=infinitiusa.com; Path=/; Max-Age=3600",
            "JSESSIONID=sid; Path=/NissanLeafJanus/rest; HttpOnly",
        ), requestUrl)
        val header = store.cookieHeaderFor(requestUrl)
        assertNotNull(header)
        assertTrue(header.contains("AWSALB=sticky"), "sticky routing cookie must be sent: $header")
        assertTrue(header.contains("JSESSIONID=sid"), "session cookie must be sent: $header")

        // A second response ROTATES one cookie only; the sticky one must survive.
        store.saveFromResponse(listOf("JSESSIONID=newsid; Path=/NissanLeafJanus/rest; HttpOnly"), requestUrl)
        val rotated = CookieStore(FakeSettingsStore().also { it.put("cookie_store", prefs.getString("cookie_store") ?: "") })
        val merged = rotated.cookieHeaderFor(requestUrl)
        assertNotNull(merged)
        assertTrue(merged.contains("AWSALB=sticky"), "sticky cookie must survive a rotation: $merged")
        assertTrue(merged.contains("JSESSIONID=newsid"), "rotated cookie must carry the new value: $merged")

        // Clearing drops everything, persisted and in memory.
        store.clear()
        assertNull(store.cookieHeaderFor(requestUrl))
        assertNull(prefs.getString("cookie_store"))
    }

    @Test
    fun expiredCookieIsRemovedFromTheStore() {
        val prefs = FakeSettingsStore()
        val store = CookieStore(prefs)
        val requestUrl = Url("https://icm.infinitiusa.com/x")
        store.saveFromResponse(listOf("AWSALB=sticky; Domain=infinitiusa.com; Path=/"), requestUrl)
        store.saveFromResponse(
            listOf("AWSALB=sticky; Domain=infinitiusa.com; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT"),
            requestUrl,
        )
        assertNull(store.cookieHeaderFor(requestUrl))
    }
}
