package leafconnect

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Central HTTP client for the Nissan Connect EV backend.
 *
 * Ktor is the multiplatform transport: OkHttp engine on Android, NSURLSession
 * on iOS. Both engines retry a failed connection on their own, which absorbs
 * the "Software caused connection abort" failures on a reused keep-alive
 * connection â€” the old code retried once by hand, which risked sending a remote
 * command twice.
 *
 * Cookies are handled here rather than by a generic cookie plugin, because the
 * Janus backend rotates only part of the cookie set per response and the store
 * must merge (see [CookieStore]).
 *
 * The API-Key / User-Agent-Key values are the public client constants of the
 * official app. They cannot be hidden in any app binary: anyone who unpacks
 * the APK or the iOS framework reads them out. They identify the client, they
 * do not authenticate a user.
 */
class NissanConnectAuth private constructor() {

    private val prefs: SettingsStore = prefsStore
    private val cookieStore = CookieStore(prefs)

    /**
     * Rounds are serialized, like the old single worker thread: concurrent
     * 401s used to race the shared token/cookie state and leave a stale token
     * in use, which produced a spurious onUnauthorized.
     */
    private val roundMutex = Mutex()

    /** Soft-login rounds since the last login (max 2; cookie rotation can need two). */
    private var softLoginCount = 0

    private val client = HttpClient(httpEngine()) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            // The battery endpoint is slow (16-60+ s server-side): 90 s of
            // socket read, and 120 s for the whole call. Both engines honour
            // these (OkHttp read timeout, NSURLSession resource timeout).
            socketTimeoutMillis = 90_000
            requestTimeoutMillis = 120_000
        }
    }

    /** Drops the session material: the auth token and the whole cookie set. */
    fun clearSession() {
        prefs.remove("token")
        cookieStore.clear()
        softLoginCount = 0
    }

    /**
     * One request round. On 401, soft-login and retry â€” up to two recovery
     * rounds â€” mirroring the original app. Cancellation is not retried.
     */
    suspend fun makeRequest(
        body: JsonObject?,
        method: String,
        path: String,
        token: CancellationToken? = null,
    ): NissanConnectResponse = roundMutex.withLock {
        var response = doRequest(body, method, path, token)
        var retries = 0
        while (response.statusCode == 401 && retries < 2 && token?.isCancelled != true) {
            handleSoftLogin(token)
            response = doRequest(body, method, path, token)
            retries++
        }
        response
    }

    suspend fun makeRequestLogin(
        body: JsonObject,
        method: String,
        path: String,
        token: CancellationToken? = null,
    ): NissanConnectResponse = roundMutex.withLock {
        softLoginCount = 0
        doRequest(body, method, path, token)
    }

    /** Re-authenticate with the stored credentials and keep the fresh token. */
    suspend fun softLogin(token: CancellationToken?): String {
        val email = CredentialStore.email ?: prefs.getString("email")
        val password = CredentialStore.password ?: prefs.getString("password")
        if (email.isNullOrBlank() || password.isNullOrBlank()) return "not_kept"

        val body = authenticateBody(email, password, "en", AppLocale.sessionCountry(prefs))
        val response = doRequest(body, "POST", "/auth/authenticationForAAS", token)
        if (response.statusCode == 401) {
            prefs.put("auto_login", "0")
            return "expired"
        }
        val json = response.body ?: return "error"
        val newToken = json.text("authToken", "")
        if (newToken.isBlank()) return if (json.int("errorCode", 0) == 1008) "expired" else "error"
        prefs.put("token", newToken)
        return "ok"
    }

    private suspend fun doRequest(
        body: JsonObject?,
        method: String,
        path: String,
        token: CancellationToken?,
    ): NissanConnectResponse {
        if (token?.isCancelled == true) return NissanConnectResponse(0, null)

        val url = Url(BASE_URL + path)
        val cookieHeader = cookieStore.cookieHeaderFor(url)
        val authToken = prefs.getString("token")?.takeIf { it.isNotEmpty() }

        val response = try {
            client.request {
                this.url(url)
                this.method = HttpMethod.parse(method)
                header("Content-Type", "application/json")
                header("APP-Check", "Android " + AppLocale.sessionCountry(prefs))
                header("API-Key", API_KEY)
                header("User-Agent-Key", USER_AGENT_KEY)
                header("Cache-Control", "no-cache, no-store, must-revalidate")
                header("Pragma", "no-cache")
                authToken?.let { header("Authorization", it) }
                cookieHeader?.let { header("Cookie", it) }
                if (body != null && method != "GET") {
                    setBody(body.toString())
                }
            }
        } catch (e: CancellationException) {
            throw e
        }

        val text = response.bodyAsText()
        cookieStore.saveFromResponse(response.headers.getAll("Set-Cookie") ?: emptyList(), url)
        return NissanConnectResponse(response.status.value, parseJsonObject(text))
    }

    private suspend fun handleSoftLogin(token: CancellationToken?) {
        if (token?.isCancelled == true) return
        if (softLoginCount > 1) return
        val outcome = softLogin(token)
        if (outcome == "ok") softLoginCount++ else softLoginCount = 2
    }

    companion object {
        private const val BASE_URL = "https://icm.infinitiusa.com/NissanLeafJanus/rest"

        // Public client constants of the official app, not user secrets: they
        // ship inside the app binary and are readable by anyone who unpacks it.
        private const val API_KEY = "RbypnRCTrt6LVkm78s/67Xl5yPVXhFnZStRjyUvT03PsKi540xhfl+Xxn/aGZsaq"
        private const val USER_AGENT_KEY = "AIzaSyDb-KX_YXWzYFchv-IDTsVAmADIBPvA8vg"

        fun getInstance(): NissanConnectAuth {
            // `lazy` is thread-safe on every platform, which is what the JVM's
            // synchronized block used to provide; construction is platform-neutral.
            return lazyInstance
        }

        private val lazyInstance: NissanConnectAuth by lazy { NissanConnectAuth() }
    }
}

