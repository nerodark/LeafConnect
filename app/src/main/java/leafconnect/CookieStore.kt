package leafconnect

import android.content.Context
import okhttp3.Cookie
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Session cookie store for the Nissan Connect API.
 *
 * OkHttp parses every `Set-Cookie` header of a response on its own (several
 * headers, Path / Domain / Expires / Secure / HttpOnly / SameSite attributes),
 * which removes the fragile parsing the old HttpURLConnection code did — that
 * code read only ONE `Set-Cookie` header and split the value on commas, so a
 * response with several cookies, or an `Expires` value containing a comma
 * ("Tue, 09 Sep 2025 ..."), silently dropped cookies.
 *
 * The backend rotates only some cookies per response (login → JSESSIONID,
 * soft-login → AWSALB/AWSALBCORS sticky routing, ...), so the store keeps the
 * full set and merges per (domain, path, name) — replacing the whole set
 * would drop the sticky-routing cookie and the WAF answers 401.
 *
 * The set is persisted (encrypted prefs) so a session survives app
 * restarts. This store is shared by the singleton NissanConnectAuth, so every
 * mutation runs under `lock`.
 */
class CookieStore(context: Context) {

    private val prefs = SecurePreferences.getInstance(context)
    private val lock = Any()

    /** In-memory mirror of the persisted set; `loaded` guards the first read. */
    private val stored = HashMap<CookieKey, StoredCookie>()
    private var loaded = false

    fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            ensureLoaded()
            val now = nowMillis()
            return stored.values
                .filter { it.isLive(now) }
                .mapNotNull { it.toCookie() }
                .filter { it.matches(url) }
                .toList()
        }
    }

    // The url is intentionally not a parameter: OkHttp hands us cookies it
    // already parsed from the response, each carrying its own domain/path —
    // the merge keys off those, so the request URL adds nothing.
    fun saveFromResponse(incoming: List<Cookie>) {
        synchronized(lock) {
            ensureLoaded()
            val now = nowMillis()
            var changed = false
            for (c in incoming) {
                val key = CookieKey(c.domain.lowercase(), c.path, c.name)
                if (c.persistent && c.expiresAt < now) {
                    // An expiry in the past is a delete instruction.
                    if (stored.remove(key) != null) changed = true
                } else {
                    stored[key] = StoredCookie(
                        name = c.name,
                        value = c.value,
                        domain = c.domain,
                        path = c.path,
                        expiresAt = if (c.persistent) c.expiresAt else NO_EXPIRY,
                        secure = c.secure,
                        httpOnly = c.httpOnly,
                        hostOnly = c.hostOnly,
                    )
                    changed = true
                }
            }
            // Prune anything that aged out while the app was closed.
            val live = stored.values.filter { it.isLive(now) }
            if (live.size != stored.size) changed = true
            stored.clear()
            live.forEach { stored[CookieKey(it.domain.lowercase(), it.path, it.name)] = it }
            if (changed) persist()
        }
    }

    fun clear() {
        synchronized(lock) {
            stored.clear()
            loaded = true
            prefs.remove(KEY)
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        val raw = prefs.getString(KEY)
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val c = StoredCookie(
                        name = o.getString("n"),
                        value = o.getString("v"),
                        domain = o.getString("d"),
                        path = o.optString("p", "/"),
                        expiresAt = o.optLong("e", NO_EXPIRY),
                        secure = o.optBoolean("s", false),
                        httpOnly = o.optBoolean("h", false),
                        hostOnly = o.optBoolean("o", false),
                    )
                    stored[CookieKey(c.domain.lowercase(), c.path, c.name)] = c
                }
            } catch (_: Exception) {
                // Corrupt store: start clean.
                stored.clear()
            }
        }
        loaded = true
    }

    private fun persist() {
        val array = JSONArray()
        for (c in stored.values) {
            val o = JSONObject()
            o.put("n", c.name)
            o.put("v", c.value)
            o.put("d", c.domain)
            o.put("p", c.path)
            if (c.expiresAt != NO_EXPIRY) o.put("e", c.expiresAt)
            if (c.secure) o.put("s", true)
            if (c.httpOnly) o.put("h", true)
            if (c.hostOnly) o.put("o", true)
            array.put(o)
        }
        prefs.put(KEY, array.toString())
    }

    private fun nowMillis(): Long = System.currentTimeMillis()

    /** One cookie entry, keyed by domain + path + name (OkHttp cookie semantics). */
    data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    ) {
        /** Live means no expiry, or an expiry (epoch millis) in the future. */
        fun isLive(now: Long): Boolean = expiresAt == NO_EXPIRY || expiresAt > now

        fun toCookie(): Cookie? = runCatching {
            val n = name
            val v = value
            val d = domain
            val p = path
            val e = expiresAt
            val sec = secure
            val ho = httpOnly
            val hst = hostOnly
            Cookie.Builder().apply {
                name(n)
                value(v)
                if (e != NO_EXPIRY) expiresAt(e)
                // hostOnlyDomain() marks the cookie as host-only in one step.
                if (hst) hostOnlyDomain(d) else domain(d)
                path(p)
                if (sec) secure()
                if (ho) httpOnly()
            }.build()
        }.getOrNull()
    }

    data class CookieKey(val domain: String, val path: String, val name: String)

    companion object {
        private const val KEY = "cookie_store"

        /** Sentinel for a session cookie with no expiry (epoch millis). */
        internal const val NO_EXPIRY = -1L
    }
}
