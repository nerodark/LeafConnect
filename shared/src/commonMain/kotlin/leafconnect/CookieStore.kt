package leafconnect

import io.ktor.http.Url
import io.ktor.http.isSecure
import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Session cookie store for the Nissan Connect API.
 *
 * OkHttp used to parse `Set-Cookie` for us; Ktor hands the raw headers to the
 * app, so the parsing lives here. The rules are OkHttp's (RFC 6265):
 *  - several `Set-Cookie` headers per response are all kept;
 *  - `Expires` values containing commas are parsed as a whole HTTP date, never
 *    split on a comma (the old HttpURLConnection code split on commas and
 *    silently dropped cookies);
 *  - the store MERGES per (domain, path, name): the backend rotates only some
 *    cookies per response, and replacing the whole set would drop the
 *    AWSALB/AWSALBCORS sticky-routing cookie â€” the WAF then answers 401.
 *
 * Persistence keeps a session alive across restarts. Every call happens inside
 * the request mutex of [NissanConnectAuth], so the map needs no locking.
 */
class CookieStore(private val store: SettingsStore) {

    private val stored = HashMap<CookieKey, StoredCookie>()
    private var loaded = false

    /** The `Cookie` header to send for a request, or null when nothing matches. */
    fun cookieHeaderFor(url: Url): String? {
        ensureLoaded()
        val now = nowMillis()
        val live = stored.values
            .filter { it.isLive(now) && matches(it, url.host, url.encodedPath, url.protocol.isSecure()) }
        if (live.isEmpty()) return null
        // OkHttp sends persistent cookies oldest-expiry first, session cookies last.
        val ordered = live.sortedBy { if (it.expiresAt == NO_EXPIRY) Long.MAX_VALUE else it.expiresAt }
        return ordered.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** Merge the response's `Set-Cookie` headers into the store. */
    fun saveFromResponse(setCookieHeaders: List<String>, requestUrl: Url) {
        ensureLoaded()
        val now = nowMillis()
        var changed = false
        for (header in setCookieHeaders) {
            val parsed = parse(header, requestUrl.host) ?: continue
            val key = CookieKey(parsed.domain.lowercase(), parsed.path, parsed.name)
            if (parsed.expiresAt != NO_EXPIRY && parsed.expiresAt < now) {
                // An expiry in the past is a delete instruction.
                if (stored.remove(key) != null) changed = true
            } else {
                stored[key] = parsed
                changed = true
            }
        }
        // Prune anything that aged out while the app was closed.
        val live = stored.values.filter { it.isLive(now) }
        if (live.size != stored.size) {
            changed = true
            stored.clear()
            live.forEach { stored[CookieKey(it.domain.lowercase(), it.path, it.name)] = it }
        }
        if (changed) persist()
    }

    fun clear() {
        stored.clear()
        loaded = true
        store.remove(KEY)
    }

    private fun ensureLoaded() {
        if (loaded) return
        val raw = store.getString(KEY)
        if (raw != null) {
            val array = parseJsonObject(raw)?.arrayAt(KEY_ARRAY)
            if (array != null) {
                for (element in array) {
                    val o = element as? kotlinx.serialization.json.JsonObject ?: continue
                    val c = StoredCookie(
                        name = o.text("n") ?: continue,
                        value = o.text("v") ?: "",
                        domain = o.text("d") ?: continue,
                        path = o.text("p", "/"),
                        expiresAt = o.long("e", NO_EXPIRY),
                        secure = o.boolean("s", false),
                        httpOnly = o.boolean("h", false),
                        hostOnly = o.boolean("o", false),
                    )
                    stored[CookieKey(c.domain.lowercase(), c.path, c.name)] = c
                }
            } else {
                // Corrupt store: start clean.
                stored.clear()
            }
        }
        loaded = true
    }

    private fun persist() {
        val array = buildJsonArray {
            for (c in stored.values) {
                add(buildJsonObject {
                    put("n", c.name)
                    put("v", c.value)
                    put("d", c.domain)
                    put("p", c.path)
                    if (c.expiresAt != NO_EXPIRY) put("e", c.expiresAt)
                    if (c.secure) put("s", true)
                    if (c.httpOnly) put("h", true)
                    if (c.hostOnly) put("o", true)
                })
            }
        }
        store.put(KEY, buildJsonObject { put(KEY_ARRAY, array) }.toString())
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

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
    }

    data class CookieKey(val domain: String, val path: String, val name: String)

    companion object {
        private const val KEY = "cookie_store"
        private const val KEY_ARRAY = "c"

        /** Sentinel for a session cookie with no expiry (epoch millis). */
        const val NO_EXPIRY = -1L

        /**
         * Parse one `Set-Cookie` header value. Returns null when it carries no
         * name=value pair, so a malformed header is skipped instead of crashing
         * a request. A cookie without a Domain attribute is host-only and gets
         * the request host as its domain (OkHttp semantics).
         */
        fun parse(header: String, requestHost: String): StoredCookie? {
            val parts = header.split(';').map { it.trim() }
            if (parts.isEmpty()) return null
            val nameValue = parts[0]
            val eq = nameValue.indexOf('=')
            if (eq <= 0) return null
            val name = nameValue.substring(0, eq)
            val value = nameValue.substring(eq + 1)

            var domain: String? = null
            var path = "/"
            var expiresAt = NO_EXPIRY
            var secure = false
            var httpOnly = false

            for (attribute in parts.drop(1)) {
                val pair = attribute.split('=', limit = 2)
                val key = pair[0].trim().lowercase()
                val raw = pair.getOrNull(1)?.trim()
                when (key) {
                    "domain" -> domain = raw?.removePrefix(".")
                    "path" -> if (!raw.isNullOrEmpty()) path = raw
                    "expires" -> raw?.let { expiresAt = parseHttpDate(it) ?: expiresAt }
                    // Max-Age wins over Expires, as RFC 6265 requires.
                    "max-age" -> raw?.toLongOrNull()?.let { expiresAt = Clock.System.now().toEpochMilliseconds() + it * 1000 }
                    "secure" -> secure = true
                    "httponly" -> httpOnly = true
                }
            }

            // No Domain attribute => host-only cookie for the request host.
            val hostOnly = domain == null
            return StoredCookie(
                name = name,
                value = value,
                domain = domain ?: requestHost,
                path = path,
                expiresAt = expiresAt,
                secure = secure,
                httpOnly = httpOnly,
                hostOnly = hostOnly,
            )
        }

        /** OkHttp cookie matching rules: host, path and Secure. */
        fun matches(cookie: StoredCookie, host: String, path: String, https: Boolean): Boolean {
            if (cookie.secure && !https) return false
            if (!domainMatches(cookie.domain, host, cookie.hostOnly)) return false
            return pathMatches(cookie.path, path)
        }

        private fun domainMatches(domain: String, host: String, hostOnly: Boolean): Boolean {
            val d = domain.removePrefix(".").lowercase()
            val h = host.lowercase()
            if (d.isEmpty()) return false
            if (hostOnly) return h == d
            if (h == d) return true
            // A domain cookie covers subdomains only.
            return h.endsWith(".$d")
        }

        private fun pathMatches(cookiePath: String, requestPath: String): Boolean {
            if (cookiePath == "/") return true
            if (requestPath == cookiePath) return true
            if (!requestPath.startsWith(cookiePath)) return false
            if (cookiePath.endsWith("/")) return true
            // "/NissanLeafJanus/rest" must not match "/NissanLeafJanus/restx":
            // the boundary character has to be a path separator (RFC 6265).
            return requestPath.length > cookiePath.length && requestPath[cookiePath.length] == '/'
        }

        /**
         * HTTP date (`Tue, 09 Sep 2025 12:00:00 GMT`, also the `09-Sep-2025`
         * variant) to epoch millis. Deliberately not a locale/date library:
         * the comma inside the value must never split the header.
         */
        fun parseHttpDate(value: String): Long? {
            var text = value.trim()
            val comma = text.indexOf(',')
            if (comma >= 0) text = text.substring(comma + 1).trim()

            // "09 Sep 2025 12:00:00 GMT" => day/month/year are separate tokens;
            // "09-Sep-2025 12:00:00 GMT" => they are hyphen-joined.
            val tokens = text.split(' ')
            val day: Int
            val month: Int
            val year: Int
            val timeToken: String
            if (tokens.firstOrNull()?.contains('-') == true) {
                val bits = tokens[0].split('-')
                if (bits.size != 3) return null
                day = bits[0].toIntOrNull() ?: return null
                month = MONTHS[bits[1].lowercase()] ?: return null
                year = bits[2].toIntOrNull() ?: return null
                timeToken = tokens.getOrNull(1) ?: return null
            } else {
                if (tokens.size < 4) return null
                day = tokens[0].toIntOrNull() ?: return null
                month = MONTHS[tokens[1].lowercase()] ?: return null
                year = tokens[2].toIntOrNull() ?: return null
                timeToken = tokens[3]
            }

            val time = timeToken.split(':')
            if (time.size != 3) return null
            val hour = time[0].toIntOrNull() ?: return null
            val minute = time[1].toIntOrNull() ?: return null
            val second = time[2].toIntOrNull() ?: return null

            val days = daysFromCivil(year, month, day)
            return (days * 86400L + hour * 3600L + minute * 60L + second) * 1000L
        }

        private val MONTHS = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        )

        /** Days since the Unix epoch for a proleptic Gregorian civil date. */
        private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 400) / 400
            val yoe = y - era * 400
            val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
            val dayOfEra = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + dayOfEra - 719468
        }
    }
}

