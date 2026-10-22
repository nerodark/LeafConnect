package leafconnect

import android.content.Context
import org.json.JSONObject

/**
 * Session manager for the Nissan Connect API.
 * Delegates HTTP to NissanConnectAuth, tracks auth state.
 */
class NissanConnectSession(private val context: Context) {

    var onUnauthorized: (() -> Unit)? = null

    // When true, 401s do NOT trigger onUnauthorized: the caller inspects
    // statusCode and self-heals (re-authenticate + retry). Used by the
    // battery-status fetch loop and the vehicle actions.
    var suppressUnauthorized = false

    private val client: NissanConnectAuth = NissanConnectAuth.getInstance(context)

    fun login(email: String, password: String, country: String): JSONObject {
        val body = JSONObject().apply {
            put("authenticate", JSONObject().apply {
                put("userid", email)
                put("password", password)
                put("brand-s", "N")
                put("language-s", "EN")
                put("country", country)
            })
        }
        val response = client.makeRequestLogin(body, "POST", "/auth/authenticationForAAS")
        val json = response.body ?: throw RuntimeException("Login failed: HTTP ${response.statusCode}")
        return json
    }

    fun reauthenticate(): String {
        val prefs = SecurePreferences.getInstance(context)
        // Credentials not kept (Keep-credentials off) is NOT an expiry: the
        // current token is still valid for this run, we just can't mint a new
        // one. Callers treat "not_kept" as "proceed with the current token".
        val email = prefs.getString("email") ?: return "not_kept"
        val password = prefs.getString("password") ?: return "not_kept"

        val body = JSONObject().apply {
            put("authenticate", JSONObject().apply {
                put("userid", email)
                put("password", password)
                put("brand-s", "N")
                put("language-s", "EN")
                put("country", "CA")
            })
        }
        val response = client.makeRequestLogin(body, "POST", "/auth/authenticationForAAS")
        if (response.statusCode == 401) {
            onUnauthorized?.invoke()
            // The credentials were rejected: clear auto-login so the next
            // launch does not immediately try to log in with credentials
            // the server no longer accepts (the login form will show the
            // checkbox unchecked).
            prefs.put("auto_login", "0")
            return "expired"
        }
        val json = response.body ?: return "error"
        val newToken = json.optString("authToken", "")
        if (newToken.isNotBlank()) prefs.put("token", newToken)
        // Refresh the stored vehicle list (names, VINs, temps, ...) from the fresh login response
        val vehicles = json.optJSONArray("vehicles")
        if (vehicles != null) prefs.put("vehicles", vehicles.toString())
        return "ok"
    }

    fun request(endpoint: String, method: String = "POST", params: JSONObject? = null): NissanConnectResponse {
        val path = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
        val response = client.makeRequest(params ?: JSONObject(), method, path)

        if (response.statusCode == 401 && !suppressUnauthorized) {
            onUnauthorized?.invoke()
        }

        return response
    }
}
