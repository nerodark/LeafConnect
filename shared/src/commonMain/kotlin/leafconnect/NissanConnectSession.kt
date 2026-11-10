package leafconnect

import kotlinx.serialization.json.JsonObject

/**
 * Session manager for the Nissan Connect API. Delegates HTTP to
 * [NissanConnectAuth] and tracks auth state.
 *
 * Login rejects a response that carries no `authToken`: Janus answers a
 * REJECTED login with HTTP 200 plus an `errorCode` (1008 = wrong credentials),
 * so credentials are proven valid only by an auth token.
 */
class NissanConnectSession {

    /**
     * Invoked when a 401 reached this session without suppression — i.e. the
     * caller did NOT self-heal. The UI routes every logout through one guarded
     * path so this can never start the login screen twice.
     */
    var onUnauthorized: (() -> Unit)? = null

    private val client: NissanConnectAuth = NissanConnectAuth.getInstance()

    private fun prefs(): SettingsStore = prefsStore

    suspend fun login(email: String, password: String, token: CancellationToken? = null): JsonObject {
        val country = AppLocale.sessionCountry(prefs())
        prefs().put(AppLocale.PREF_COUNTRY, country)
        val response = client.makeRequestLogin(
            authenticateBody(email, password, AppLocale.languageCode(), country),
            "POST",
            "/auth/authenticationForAAS",
            token,
        )
        return response.body ?: throw IllegalStateException("Login failed: HTTP ${response.statusCode}")
    }

    /**
     * Re-authenticate with the stored credentials. Returns "ok", "expired"
     * (credentials rejected), "not_kept" (nothing to re-auth with),
     * "canceled" (the round was canceled in the meantime) or "error".
     *
     * The password is read from RAM ([CredentialStore]) first, then from the
     * encrypted store, so re-authentication can heal a 401 even after a process
     * restart. Only when neither exists is there nothing to re-auth with: the
     * caller keeps using the current token, and a 401 that cannot be healed
     * ends on the login screen.
     */
    suspend fun reauthenticate(token: CancellationToken? = null): String {
        val prefs = prefs()
        val email = CredentialStore.email ?: prefs.getString("email")
        val password = CredentialStore.password ?: prefs.getString("password")
        if (email.isNullOrBlank() || password.isNullOrBlank()) return "not_kept"

        val response = client.makeRequestLogin(
            authenticateBody(email, password, AppLocale.languageCode(), AppLocale.sessionCountry(prefs)),
            "POST",
            "/auth/authenticationForAAS",
            token,
        )
        // A canceled round must not read as a failed re-authentication: the
        // client maps every non-"canceled" result to onSessionDead, which would
        // log the user out for pressing CANCEL.
        if (token?.isCancelled == true) return "canceled"
        if (response.statusCode == 401) {
            prefs.put("auto_login", "0")
            return "expired"
        }
        val json = response.body ?: return "error"
        val newToken = json.text("authToken", "")
        if (newToken.isBlank()) {
            if (json.int("errorCode", 0) == 1008) {
                // Credentials genuinely rejected: disarm auto-login so the login
                // screen does not auto-retry a bad password.
                prefs.put("auto_login", "0")
                return "expired"
            }
            // Transient failure: the session is unusable now (the caller logs
            // out), but auto-login stays armed so a later start retries on its own.
            return "error"
        }
        prefs.put("token", newToken)
        json.arrayAt("vehicles")?.let { prefs.put("vehicles", it.toString()) }
        return "ok"
    }

    /**
     * Central request path. `suppressUnauthorized` is per-request (owned by the
     * caller), not shared mutable state: the self-healing caller inspects the
     * status code itself, so 401s must not fire onUnauthorized for it.
     */
    suspend fun request(
        endpoint: String,
        method: String = "POST",
        params: JsonObject? = null,
        token: CancellationToken? = null,
        suppressUnauthorized: Boolean = false,
    ): NissanConnectResponse {
        val path = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
        val response = client.makeRequest(params ?: EMPTY_BODY, method, path, token)
        if (response.statusCode == 401 && !suppressUnauthorized) {
            onUnauthorized?.invoke()
        }
        return response
    }

    fun clearSession() {
        client.clearSession()
    }

    /** Empty JSON object body, as the endpoints expect for POSTs with no payload. */
    private val EMPTY_BODY: JsonObject = kotlinx.serialization.json.buildJsonObject { }
}
