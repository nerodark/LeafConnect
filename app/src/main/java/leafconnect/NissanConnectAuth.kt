package leafconnect

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central HTTP client for the Nissan Connect EV backend.
 *
 * OkHttp is used (instead of HttpURLConnection) for two reasons the old code
 * could not solve by itself:
 *  - every `Set-Cookie` header of a response is parsed properly (multiple
 *    headers + attributes), see CookieStore;
 *  - `Call.cancel()` aborts a blocking socket read, so a per-request
 *    CancellationToken actually stops a slow request. Thread.interrupt() never
 *    does that for a socket read.
 *
 * The API-Key / User-Agent-Key values are the public client constants of the
 * official app. They CANNOT be hidden in an Android app: anyone who extracts
 * the APK reads them out of the binary. They are not user secrets, and the
 * old misleading `encryptnew()` name (it returned a literal constant, it
 * encrypted nothing) is gone.
 */
class NissanConnectAuth private constructor(context: Context) {

    private val prefs: SecurePreferences = SecurePreferences.getInstance(context)
    private val cookieStore = CookieStore(context)

    /**
     * Shared by the singleton instance across every request thread, so it is
     * an AtomicInteger (plain Int was read/written from several threads).
     */
    private val softLoginCount = AtomicInteger(0)

    private val cookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore.loadForRequest(url)
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
            cookieStore.saveFromResponse(cookies)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // The battery endpoint is slow (16-60+ s on the server side); a short
        // read timeout caused silent SocketTimeoutExceptions on slow rounds.
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    /** Drops the session material: the auth token and the whole cookie set. */
    fun clearSession() {
        prefs.remove("token")
        cookieStore.clear()
        softLoginCount.set(0)
    }

    fun makeRequest(
        body: JSONObject,
        method: String,
        path: String,
        token: CancellationToken? = null,
    ): NissanConnectResponse {
        var response = doRequest(body, method, path, token)
        // Mirror the original app: on 401, soft-login then retry the request
        // with the fresh token (up to 2 recovery rounds — cookie rotation can
        // need more than one round right after a re-login).
        var retries = 0
        while (response.statusCode == 401 && retries < 2 && token?.isCancelled != true) {
            handleSoftLogin(token)
            response = doRequest(body, method, path, token)
            retries++
        }
        return response
    }

    fun makeRequestLogin(
        body: JSONObject,
        method: String,
        path: String,
        token: CancellationToken? = null,
    ): NissanConnectResponse = makeRequest(body, method, path, token)

    private fun doRequest(
        body: JSONObject,
        method: String,
        path: String,
        token: CancellationToken?,
    ): NissanConnectResponse {
        // "Software caused connection abort": the server closed a keep-alive
        // socket the client was about to reuse. One retry on a fresh
        // connection is the standard remedy and resolves it.
        var last: SocketException? = null
        repeat(2) {
            if (token?.isCancelled == true) return NissanConnectResponse(0, null)
            try {
                return sendRequest(body, method, path, token)
            } catch (e: SocketException) {
                last = e
            }
        }
        throw last ?: SocketException("connection aborted")
    }

    private fun sendRequest(
        body: JSONObject,
        method: String,
        path: String,
        token: CancellationToken?,
    ): NissanConnectResponse {
        val url = BASE_URL + path
        val requestBody =
            if (method.equals("POST", ignoreCase = true)) body.toString().toRequestBody(CONTENT_TYPE)
            else null
        val builder = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("Content-Type", "application/json")
            .header("APP-Check", "Android CA")
            .header("API-Key", API_KEY)
            .header("User-Agent-Key", USER_AGENT_KEY)
            .header("Cache-Control", "no-cache, no-store, must-revalidate")
            .header("Pragma", "no-cache")
        prefs.getString("token")?.let { builder.header("Authorization", it) }
        val call = client.newCall(builder.build())
        // Registering before execute() means a cancel that lands while this
        // request is queued aborts the socket read as soon as it starts.
        token?.register(call)
        val response = call.execute()
        val text = response.body?.string() ?: ""
        val json = try { JSONObject(text) } catch (_: Exception) { null }
        return NissanConnectResponse(response.code, json)
    }

    private fun handleSoftLogin(token: CancellationToken?) {
        // The original caps this at 3 attempts; we use 2 to avoid recursion.
        if (softLoginCount.get() >= 2) {
            softLoginCount.set(0)
            return
        }
        softLoginCount.incrementAndGet()
        // RAM first, then the encrypted store (Keep credentials) so the
        // session can also heal after a process restart.
        val email = CredentialStore.email ?: prefs.getString("email")
        val password = CredentialStore.password ?: prefs.getString("password")
        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            // Nothing to soft-login with; the current token stays as it is.
            softLoginCount.set(0)
            return
        }
        val body = JSONObject().apply {
            put("authenticate", JSONObject().apply {
                put("userid", email)
                put("password", password)
                put("brand-s", "N")
                put("language-s", "en")
                put("country", "CA")
            })
        }
        val response = doRequest(body, "POST", "/auth/softLoginforAAS", token)
        // Janus also answers a rejected soft-login with HTTP 200 + errorCode
        // and no authToken: only a response that actually carries an authToken
        // healed the session, so the attempt counter resets only then.
        val newToken = response.body?.optString("authToken", "") ?: ""
        if (response.statusCode in 200..299 && newToken.isNotBlank()) softLoginCount.set(0)
        if (newToken.isNotBlank()) prefs.put("token", newToken)
    }

    companion object {
        private const val BASE_URL = "https://icm.infinitiusa.com/NissanLeafJanus/rest"

        // Public client constants of the official app, not user secrets: they
        // ship inside the APK and are readable by anyone who unpacks it.
        private const val API_KEY = "RbypnRCTrt6LVkm78s/67Xl5yPVXhFnZStRjyUvT03PsKi540xhfl+Xxn/aGZsaq"
        private const val USER_AGENT_KEY = "AIzaSyDb-KX_YXWzYFchv-IDTsVAmADIBPvA8vg"

        private val CONTENT_TYPE = "application/json".toMediaType()

        @Volatile
        private var instance: NissanConnectAuth? = null

        fun getInstance(context: Context): NissanConnectAuth {
            return instance ?: synchronized(this) {
                instance ?: NissanConnectAuth(context.applicationContext).also { instance = it }
            }
        }
    }
}
