package leafconnect

import kotlin.concurrent.Volatile

/**
 * The account credentials for the current process, held in RAM.
 *
 * This object is the RAM half of the credential store. When the user ticks
 * "Keep credentials", the email and the password are additionally persisted —
 * encrypted by the platform store — so auto-login, the pre-filled form, and
 * session self-healing survive a process restart; unchecking it drops both.
 *
 * Consequence for the backend flows: soft-login and re-authentication run from
 * RAM first and fall back to the encrypted store. After a process restart with
 * nothing persisted, the persisted session token is used as-is; when the server
 * rejects it, the app logs out to the login screen
 * (`NissanConnectSession.reauthenticate` returns "not_kept", which callers
 * treat as "keep going with the current token").
 */
object CredentialStore {

    @Volatile
    var email: String? = null

    @Volatile
    var password: String? = null

    fun set(email: String, password: String) {
        this.email = email
        this.password = password
    }

    fun clear() {
        email = null
        password = null
    }
}
