package leafconnect

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.leafconnect.R
import org.json.JSONArray
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private lateinit var session: NissanConnectSession
    private lateinit var overlay: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        setContentView(R.layout.activity_login)

        // Splash hold: the system splash is dismissed at the first frame, so a
        // white overlay with the icon keeps the splash look for 1s.
        val splashOverlay = findViewById<FrameLayout>(R.id.splash_overlay)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            splashOverlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction { splashOverlay.visibility = View.GONE }
                .start()
        }, 1_000)

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
            // Unchecking Keep credentials clears the STORED credentials
            // immediately (the fields on screen stay as they are).
            if (!cbKeep.isChecked) {
                prefs.remove("email")
                prefs.remove("password")
            }
        }
        findViewById<View>(R.id.row_auto_login).setOnClickListener {
            cbAuto.isChecked = !cbAuto.isChecked
            prefs.put("auto_login", if (cbAuto.isChecked) "1" else "0")
        }

        // Pre-fill saved credentials (Keep credentials) and, when auto-login
        // is on, log straight into the home screen on launch. The checkbox
        // states themselves persist across launches.
        cbKeep.isChecked = (prefs.getString("keep_credentials") ?: "0") == "1"
        cbAuto.isChecked = (prefs.getString("auto_login") ?: "0") == "1"
        val savedEmail = prefs.getString("email") ?: ""
        val savedPass = prefs.getString("password") ?: ""
        if (savedEmail.isNotEmpty()) etUsername.setText(savedEmail)
        if (savedPass.isNotEmpty()) etPassword.setText(savedPass)
        // Auto-login when the preference is checked and credentials are
        // present — except right after a manual LOGOUT, which must land on
        // this screen and stay (the preference stays checked).
        val justOut = HomeActivity.justLoggedOut
        HomeActivity.justLoggedOut = false
        if (!justOut && (prefs.getString("auto_login") ?: "0") == "1" && savedEmail.isNotEmpty() && savedPass.isNotEmpty()) {
            doLogin(savedEmail, savedPass, keepCreds = true)
        }

        btnLogin.setOnClickListener {
            val email = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            if (email.isEmpty()) {
                etUsername.error = "Email is required"
                etUsername.requestFocus()
                return@setOnClickListener
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                etUsername.error = "Enter a valid email address"
                etUsername.requestFocus()
                return@setOnClickListener
            }
            etUsername.error = null
            if (password.isEmpty()) {
                etPassword.error = "Password is required"
                etPassword.requestFocus()
                return@setOnClickListener
            }
            etPassword.error = null
            doLogin(email, password, keepCreds = cbKeep.isChecked)
        }
    }

    private fun doLogin(email: String, password: String, keepCreds: Boolean) {
        showOverlay()
        Thread {
            try {
                val result = session.login(email, password, "CA")

                val authToken = result.optString("authToken", "")
                if (authToken.isEmpty()) {
                    runOnUiThread { showError("Login failed. Check your credentials.") }
                    hideOverlay()
                    return@Thread
                }

                val prefs = SecurePreferences.getInstance(this)
                prefs.put("email", email)
                prefs.put("password", password)
                prefs.put("token", authToken)
                // Neither checkbox preference is written on login — both are
                // persisted only when the user toggles their box.
                // Keep credentials: only the checkbox decides whether the
                // credentials survive this login.
                if (!keepCreds) {
                    prefs.remove("email")
                    prefs.remove("password")
                }
                prefs.put("accountId", result.optString("accountID", ""))
                // Bump the login generation: the home screen fetches live
                // battery data only when a login is newer than the cached
                // data. Wake / rotation / restore carry no new login.
                val loginGen = (prefs.getString("login_gen") ?: "0").toLong()
                prefs.put("login_gen", (loginGen + 1).toString())

                // Store vehicles
                val vehicles = result.optJSONArray("vehicles")
                if (vehicles != null) {
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

                // Auto-refresh: reauthenticate to get live data
                val liveResult = session.reauthenticate()
                if (liveResult == "ok") {
                    val livePrefs = SecurePreferences.getInstance(this)
                    val liveEmail = livePrefs.getString("email") ?: email
                    val livePass = livePrefs.getString("password") ?: password
                    val liveBody = JSONObject().apply {
                        put("authenticate", JSONObject().apply {
                            put("userid", liveEmail)
                            put("password", livePass)
                            put("brand-s", "N")
                            put("language-s", "EN")
                            put("country", "CA")
                        })
                    }
                    val liveResp = session.request("/auth/authenticationForAAS", "POST", liveBody)
                    val liveJson = liveResp.body
                    if (liveJson != null) {
                        val liveToken = liveJson.optString("authToken", "")
                        if (liveToken.isNotEmpty()) prefs.put("token", liveToken)
                        val liveAccountId = liveJson.optString("accountID", "")
                        if (liveAccountId.isNotEmpty()) prefs.put("accountId", liveAccountId)
                        val liveVehicles = liveJson.optJSONArray("vehicles")
                        if (liveVehicles != null) {
                            val storeVehicles = JSONArray()
                            for (i in 0 until liveVehicles.length()) {
                                val v = liveVehicles.getJSONObject(i)
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
                    }
                }

                // Home appears immediately; it fetches the live battery status
                // itself, keeping its spinner up until the data arrives.
                runOnUiThread {
                    hideOverlay()
                    startActivity(Intent(this, HomeActivity::class.java).putExtra("from_login", true))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideOverlay()
                    showError("Connection error: ${e.message}")
                }
            }
        }.start()
    }

    private fun showOverlay() {
        runOnUiThread { overlay.visibility = View.VISIBLE }
    }

    private fun hideOverlay() {
        runOnUiThread { overlay.visibility = View.GONE }
    }

    private fun showError(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
