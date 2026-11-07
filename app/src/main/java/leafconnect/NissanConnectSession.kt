package leafconnect

import android.content.Context
import org.json.JSONObject

/**
 * Session manager for the Nissan Connect API.
 * Delegates HTTP to NissanConnectAuth, tracks auth state.
 */
class NissanConnectSession(private val context: Context) {

    /**
     * Invoked (on the calling worker thread) when a 401 reached this session
     * without suppression — i.e. the caller did NOT self-heal. HomeActivity
     * routes every logout through one guarded path so this can never start the
     * login screen twice.
     */
    var onUnauthorized: (() -> Unit)? = null

    private val client: NissanConnectAuth = NissanConnectAuth.getInstance(context)

    private fun prefs() = SecurePreferences.getInstance(context)

    fun login(email: String, password: String, token: CancellationToken? = null): JSONObject {
        val country = AppLocale.sessionCountry(prefs())
        prefs().put(AppLocale.PREF_COUNTRY, country)
        val body = JSONObject().apply {
            put("authenticate", JSONObject().apply {
                put("userid", email)
                put("password", password)
                put("brand-s", "N")
                put("language-s", AppLocale.languageCode())
                put("country", country)
            })
        }
        val response = client.makeRequestLogin(body, "POST", "/auth/authenticationForAAS", token)
        return response.body ?: throw RuntimeException("Login failed: HTTP ${response.statusCode}")
    }

    /**
     * Re-authenticate with the stored credentials. Returns "ok", "expired"
     * (credentials rejected), "not_kept" (nothing to re-auth with),
     * "canceled" (the round's token was canceled in the meantime) or "error".
     *
     * The password is read from RAM (CredentialStore) first, then from the
     * encrypted store (Keep credentials), so re-authentication can heal a
     * 401 even after a process restart. Only when neither exists is there
     * nothing to re-auth with: the caller keeps using the current token, and
     * a 401 that cannot be healed ends in the login screen.
     */
    fun reauthenticate(token: CancellationToken? = null): String {
        val prefs = prefs()
        val email = CredentialStore.email ?: prefs.getString("email")
        val password = CredentialStore.password ?: prefs.getString("password")
        if (email.isNullOrBlank() || password.isNullOrBlank()) return "not_kept"

        val body = JSONObject().apply {
            put("authenticate", JSONObject().apply {
                put("userid", email)
                put("password", password)
                put("brand-s", "N")
                put("language-s", AppLocale.languageCode())
                put("country", AppLocale.sessionCountry(prefs))
            })
        }
        val response = client.makeRequestLogin(body, "POST", "/auth/authenticationForAAS", token)
        // A canceled round must not read as a failed re-authentication: the
        // client maps every non-"ok" result except "canceled" to
        // onSessionDead, which would log the user out for pressing CANCEL.
        if (token?.isCancelled == true) return "canceled"
        if (response.statusCode == 401) {
            prefs.put("auto_login", "0")
            return "expired"
        }
        // Janus answers a REJECTED login with HTTP 200, not 401: the body
        // carries errorCode (1008 = wrong credentials, 910 = transient
        // backend error) and no authToken. Credentials are proven valid only
        // by an authToken — a 200 without one must never read as success.
        val json = response.body ?: return "error"
        val newToken = json.optString("authToken", "")
        if (newToken.isBlank()) {
            if (json.optInt("errorCode", 0) == 1008) {
                // Credentials genuinely rejected: disarm auto-login so the
                // login screen does not auto-retry a bad password, and let
                // the caller log out to that screen.
                prefs.put("auto_login", "0")
                return "expired"
            }
            // Transient failure: the session is unusable now (the caller
            // logs out), but auto-login stays armed so a later start
            // retries on its own.
            return "error"
        }
        prefs.put("token", newToken)
        val vehicles = json.optJSONArray("vehicles")
        if (vehicles != null) prefs.put("vehicles", vehicles.toString())
        return "ok"
    }

    /**
     * Central request path. `suppressUnauthorized` is per-request (owned by
     * the caller), not a shared mutable flag: the self-healing caller
     * (NissanConnectClient) inspects the status code itself, so 401s must not
     * fire onUnauthorized for it.
     */
    fun request(
        endpoint: String,
        method: String = "POST",
        params: JSONObject? = null,
        token: CancellationToken? = null,
        suppressUnauthorized: Boolean = false,
    ): NissanConnectResponse {
        val path = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
        val response = client.makeRequest(params ?: JSONObject(), method, path, token)
        if (response.statusCode == 401 && !suppressUnauthorized) {
            onUnauthorized?.invoke()
        }
        return response
    }

    fun clearSession() {
        client.clearSession()
    }
}
