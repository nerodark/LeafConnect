package leafconnect

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.leafconnect.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Login screen.
 *
 * Security rule for credentials: the password lives in CredentialStore (RAM)
 * for the lifetime of the process, so the session can be re-established
 * between launches. When "Keep credentials" is on, BOTH the email and the
 * password are additionally persisted — encrypted by the Keystore-backed
 * store — so auto-login survives a process restart; unchecking it drops both.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var session: NissanConnectSession
    private lateinit var overlay: FrameLayout

    /** Splash fade callback, tracked so onDestroy can drop it. */
    private val splashHandler = Handler(Looper.getMainLooper())

    /**
     * Login runs on a worker thread; the token lets a request be aborted for
     * real (Call.cancel) when the activity is destroyed. Thread.interrupt()
     * never aborted the blocking socket read.
     */
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var loginToken: CancellationToken? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        setContentView(R.layout.activity_login)

        // Splash hold: the system splash is dismissed at the first frame, so a
        // white overlay with the icon keeps the splash look for 1s. First
        // creation only: this activity survives rotation via configChanges,
        // but it is still RECREATED on process restore and on config
        // changes not covered by configChanges (density, locale, ...); on a
        // recreation the user is already looking at the form, so no second
        // splash.
        val splashOverlay = findViewById<FrameLayout>(R.id.splash_overlay)
        if (savedInstanceState == null) {
            splashHandler.postDelayed({
                splashOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { splashOverlay.visibility = View.GONE }
                    .start()
            }, 1_000)
        } else {
            splashOverlay.visibility = View.GONE
        }

        overlay = findViewById(R.id.overlay_login)
        session = NissanConnectSession(this)

        val etUsername = findViewById<EditText>(R.id.et_username)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val btnLogin = findViewById<Button>(R.id.btn_login)
        val cbKeep = findViewById<CheckBox>(R.id.cb_keep_credentials)
        val cbAuto = findViewById<CheckBox>(R.id.cb_auto_login)
        val prefs = SecurePreferences.getInstance(this)

        // The whole row (box + text) toggles the checkbox. Auto-login is
        // persisted the moment the box is toggled — NOT on login — so the
        // preference only changes when the user checks (or unchecks) it.
        findViewById<View>(R.id.row_keep_credentials).setOnClickListener {
            cbKeep.isChecked = !cbKeep.isChecked
            prefs.put("keep_credentials", if (cbKeep.isChecked) "1" else "0")
            // Unchecking Keep credentials clears everything stored (the fields
            // on screen stay as they are).
            if (!cbKeep.isChecked) {
                prefs.remove("email")
                prefs.remove("password")
            }
        }
        findViewById<View>(R.id.row_auto_login).setOnClickListener {
            cbAuto.isChecked = !cbAuto.isChecked
            prefs.put("auto_login", if (cbAuto.isChecked) "1" else "0")
        }

        // Account region: the backend ties an account to Canada or the United
        // States and answers a mismatch with its generic "wrong credentials"
        // error. The selector starts from the stored region (or the device
        // locale on a fresh install) and writes the choice the moment it
        // changes. So a sign-in always sends the region
        // shown, and soft login / re-authentication keep using it.
        val rgRegion = findViewById<RadioGroup>(R.id.rg_region)
        val rbRegionCanada = findViewById<RadioButton>(R.id.rb_region_canada)
        val rbRegionUs = findViewById<RadioButton>(R.id.rb_region_us)
        if (AppLocale.sessionCountry(prefs) == AppLocale.COUNTRY_US) {
            rbRegionUs.isChecked = true
        } else {
            rbRegionCanada.isChecked = true
        }
        rgRegion.setOnCheckedChangeListener { _, checkedId ->
            prefs.put(
                AppLocale.PREF_COUNTRY,
                if (checkedId == R.id.rb_region_us) AppLocale.COUNTRY_US else AppLocale.COUNTRY_CA,
            )
        }

        // Pre-fill the saved credentials (Keep credentials) and honor
        // auto-login. The checkbox states themselves persist across launches.
        cbKeep.isChecked = (prefs.getString("keep_credentials") ?: "0") == "1"
        cbAuto.isChecked = (prefs.getString("auto_login") ?: "0") == "1"
        val savedEmail = prefs.getString("email") ?: ""
        if (savedEmail.isNotEmpty()) etUsername.setText(savedEmail)
        etPassword.setText(CredentialStore.password ?: prefs.getString("password") ?: "")

        // Auto-login when the preference is checked — except right after a
        // manual LOGOUT, which must land on this screen and stay (the logout
        // unchecks the preference; the flag pins the form regardless).
        //
        // The password is read from RAM first, falling back to the encrypted
        // store (Keep credentials), so auto-login works after a process
        // restart. Without any password there is still one usable path:
        // a session token exists → open Home, which uses the token as-is and
        // self-heals/re-logs-in on a 401 it cannot fix. A cold start with
        // auto-login and neither has nothing to log in with, so the form is
        // shown with the email pre-filled.
        val justOut = HomeActivity.justLoggedOut
        HomeActivity.justLoggedOut = false
        if (!justOut && (prefs.getString("auto_login") ?: "0") == "1") {
            val storedPassword = CredentialStore.password ?: prefs.getString("password")
            if (savedEmail.isNotEmpty() && !storedPassword.isNullOrEmpty()) {
                doLogin(savedEmail, storedPassword, keepCreds = true)
            } else if (!(prefs.getString("token") ?: "").isEmpty()) {
                openHome(fromLogin = false)
            }
        }

        btnLogin.setOnClickListener {
            val email = etUsername.text.toString().trim()
            // The password is used EXACTLY as typed: trailing/leading spaces
            // are part of the password, so trimming it can silently break a
            // valid login.
            val password = etPassword.text.toString()
            if (email.isEmpty()) {
                etUsername.error = getString(R.string.email_required)
                etUsername.requestFocus()
                return@setOnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                etUsername.error = getString(R.string.email_invalid)
                etUsername.requestFocus()
                return@setOnClickListener
            }
            etUsername.error = null
            if (password.isEmpty()) {
                etPassword.error = getString(R.string.password_required)
                etPassword.requestFocus()
                return@setOnClickListener
            }
            etPassword.error = null
            doLogin(email, password, keepCreds = cbKeep.isChecked)
        }
    }

    override fun onDestroy() {
        splashHandler.removeCallbacksAndMessages(null)
        loginToken?.cancel()
        loginToken = null
        ioExecutor.shutdown()
        try {
            ioExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!ioExecutor.isTerminated) ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun doLogin(email: String, password: String, keepCreds: Boolean) {
        showOverlay()
        val token = CancellationToken()
        loginToken = token
        ioExecutor.submit {
            try {
                val result = session.login(email, password, token)

                val authToken = result.optString("authToken", "")
                if (authToken.isEmpty()) {
                    runOnUiThread {
                        hideOverlay()
                        showError(R.string.login_failed)
                    }
                    return@submit
                }

                val prefs = SecurePreferences.getInstance(this)
                // CredentialStore keeps the password in RAM for soft-login /
                // re-authentication in this process. With Keep credentials on
                // it is additionally persisted (encrypted) so auto-login
                // survives a process restart; off drops both values.
                CredentialStore.set(email, password)
                if (keepCreds) {
                    prefs.put("email", email)
                    prefs.put("password", password)
                } else {
                    prefs.remove("email")
                    prefs.remove("password")
                }
                prefs.put("token", authToken)
                // Neither checkbox preference is written on login — both are
                // persisted only when the user toggles their box.
                prefs.put("accountId", result.optString("accountID", ""))
                // Bump the login generation: the home screen fetches live
                // battery data only when a login is newer than the cached
                // data. Wake / rotation / restore carry no new login.
                val loginGen = (prefs.getString("login_gen") ?: "0").toLong()
                prefs.put("login_gen", (loginGen + 1).toString())
                storeVehicles(result.optJSONArray("vehicles"), prefs)

                // No re-auth round-trip here: the login response is the live
                // session. Home fetches battery status itself and self-heals
                // a 401 through the client's central re-auth path.
                runOnUiThread {
                    hideOverlay()
                    openHome(fromLogin = true)
                }
            } catch (e: Exception) {
                // A canceled login (activity finishing) throws from the
                // aborted socket read: the screen is going away, so no error
                // UI is owed.
                if (!token.isCancelled) {
                    runOnUiThread {
                        hideOverlay()
                        showError(R.string.connection_error, e.message ?: "")
                    }
                }
            }
        }
    }

    /** Trim the vehicle list down to the fields Home needs (keeps prefs small). */
    private fun storeVehicles(vehicles: JSONArray?, prefs: SecurePreferences) {
        if (vehicles == null) return
        val storeVehicles = JSONArray()
        for (i in 0 until vehicles.length()) {
            val v = vehicles.getJSONObject(i)
            val sv = JSONObject()
            sv.put("uvi", v.optString("uvi", ""))
            sv.put("nickname", v.optString("nickname", ""))
            sv.put("modelname", v.optString("modelname", ""))
            sv.put("modelyear", v.optString("modelyear", ""))
            sv.put("interiorTempRecords", v.optJSONObject("interiorTempRecords") ?: JSONObject())
            storeVehicles.put(sv)
        }
        prefs.put("vehicles", storeVehicles.toString())
    }

    private fun openHome(fromLogin: Boolean) {
        startActivity(Intent(this, HomeActivity::class.java).putExtra("from_login", fromLogin))
        finish()
    }

    private fun showOverlay() {
        runOnUiThread { overlay.visibility = View.VISIBLE }
    }

    private fun hideOverlay() {
        runOnUiThread { overlay.visibility = View.GONE }
    }

    private fun showError(res: Int, vararg args: String) {
        Toast.makeText(this, getString(res, *args), Toast.LENGTH_LONG).show()
    }
}
