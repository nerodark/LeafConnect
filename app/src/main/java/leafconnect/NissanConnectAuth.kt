package leafconnect

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Central HTTP client for the Nissan Connect EV backend.
 * Builds the request URL, applies the standard header set
 * (including the hard-coded API-Key and APP-Check), and reads the response.
 */
class NissanConnectAuth private constructor(context: Context) {

    companion object {
        private const val BASE_URL = "https://icm.infinitiusa.com/NissanLeafJanus/rest"

        @Volatile
        private var instance: NissanConnectAuth? = null

        fun getInstance(context: Context): NissanConnectAuth {
            return instance ?: synchronized(this) {
                instance ?: NissanConnectAuth(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SecurePreferences = SecurePreferences.getInstance(context)
    private var connectTimeoutMs: Int = 15_000
    // The battery endpoint is slow (16-60+ s on the server side); 30 s caused
    // silent SocketTimeoutExceptions on slow rounds.
    private var readTimeoutMs: Int = 90_000
    private var softLoginCount = 0

    fun makeRequest(body: JSONObject, method: String, path: String): NissanConnectResponse {
        var response = doRequest(body, method, path)
        // Mirror the original app: on 401, soft-login then retry the request with
        // the fresh token (up to 2 recovery rounds — cookie rotation can need more
        // than one round right after a re-login).
        var retries = 0
        while (response.statusCode == 401 && retries < 2) {
            handleSoftLogin()
            response = doRequest(body, method, path)
            retries++
        }
        return response
    }

    private fun doRequest(body: JSONObject, method: String, path: String): NissanConnectResponse {
        // "Software caused connection abort": the server closed a keep-alive
        // socket that the client was about to reuse. One retry on a fresh
        // connection is the standard remedy and resolves it.
        var last: SocketException? = null
        repeat(2) {
            try {
                return sendRequest(body, method, path)
            } catch (e: SocketException) {
                last = e
            }
        }
        throw last ?: SocketException("connection aborted")
    }

    private fun sendRequest(body: JSONObject, method: String, path: String): NissanConnectResponse {
        val fullUrl = BASE_URL + path
        val connection = (URL(fullUrl).openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            prepareRequestHeader(this)
            if (method.equals("POST", ignoreCase = true)) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        handleCookies(connection)
        connection.disconnect()
        val json = try { JSONObject(text) } catch (_: Exception) { null }
        return NissanConnectResponse(code, json)
    }

    fun makeRequestLogin(body: JSONObject, method: String, path: String): NissanConnectResponse =
        makeRequest(body, method, path)

    fun encryptnew(): String = "RbypnRCTrt6LVkm78s/67Xl5yPVXhFnZStRjyUvT03PsKi540xhfl+Xxn/aGZsaq"

    private fun prepareRequestHeader(connection: HttpsURLConnection) {
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("APP-Check", "Android CA")
        val token = prefs.getString("token")
        if (token != null) connection.setRequestProperty("Authorization", token)
        connection.setRequestProperty("API-Key", encryptnew())
        connection.setRequestProperty("User-Agent-Key", "AIzaSyDb-KX_YXWzYFchv-IDTsVAmADIBPvA8vg")
        // The original app stores the full cookie set (JSESSIONID + AWSALB sticky
        // routing) from the login Set-Cookie and re-sends it verbatim.
        val cookies = prefs.getString("cookies")
        if (!cookies.isNullOrBlank()) {
            connection.setRequestProperty("Cookie", cookies)
        } else {
            val jsession = prefs.getString("JsessionId")
            if (!jsession.isNullOrBlank()) {
                connection.setRequestProperty("Cookie", "JSESSIONID=$jsession")
            }
        }
    }

    private fun handleCookies(connection: HttpURLConnection) {
        val setCookie = connection.getHeaderField("Set-Cookie") ?: return
        CookieManager.getInstance().setCookie(connection.url.toString(), setCookie)
        // Set-Cookie may contain several comma-separated cookie sets, each with
        // attributes (Path, Expires, ...). Extract just the real name=value pairs.
        val cookieAttributes = setOf("Path", "Expires", "Domain", "Secure", "HttpOnly", "Max-Age", "SameSite", "Version")
        val newPairs = setCookie.split(",")
            .map { it.substringBefore(";").trim() }
            .mapNotNull { pair ->
                val name = pair.substringBefore("=").trim()
                if (name.isEmpty() || !pair.contains("=") || cookieAttributes.contains(name)) null else pair
            }
        if (newPairs.isNotEmpty()) {
            // Merge with the stored cookie set: each response rotates only some
            // cookies (login -> JSESSIONID, soft-login -> AWSALBCORS, ...), so
            // replacing the whole set would drop the rest and the WAF rejects
            // the request (401 Unauthorized).
            val merged = (prefs.getString("cookies") ?: "").split("; ")
                .filter { it.contains("=") }
                .map { it.trim() }
                .toMutableList()
            for (np in newPairs) {
                val name = np.substringBefore("=").trim()
                val idx = merged.indexOfFirst { it.startsWith("$name=") }
                if (idx >= 0) merged[idx] = np else merged.add(np)
            }
            prefs.put("cookies", merged.joinToString("; "))
        }
        val jsession = newPairs.firstOrNull { it.startsWith("JSESSIONID=") }
        if (jsession != null) prefs.put("JsessionId", jsession.removePrefix("JSESSIONID=").trim())
    }

    private fun handleSoftLogin() {
        // Mirror the original: on 401, re-authenticate via the soft-login endpoint.
        // The original caps this at 3 attempts; we use 2 to avoid recursion.
        if (softLoginCount >= 2) {
            softLoginCount = 0
            prefs.put("JsessionId", "")
            return
        }
        softLoginCount++
        val email = prefs.getString("email")
        val password = prefs.getString("password")
        if (email == null || password == null) {
            softLoginCount = 0
            prefs.put("JsessionId", "")
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
        val response = doRequest(body, "POST", "/auth/softLoginforAAS")
        if (response.statusCode in 200..299) softLoginCount = 0
        response.body?.let { json ->
            val newToken = json.optString("authToken", "")
            if (newToken.isNotBlank()) prefs.put("token", newToken)
        }
    }
}
