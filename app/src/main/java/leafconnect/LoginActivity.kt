package leafconnect

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
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

        overlay = findViewById(R.id.overlay_login)
        session = NissanConnectSession(this)

        val etUsername = findViewById<EditText>(R.id.et_username)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val btnLogin = findViewById<Button>(R.id.btn_login)

        btnLogin.setOnClickListener {
            val email = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            if (email.isEmpty() || password.isEmpty()) return@setOnClickListener
            doLogin(email, password)
        }
    }

    private fun doLogin(email: String, password: String) {
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
                    startActivity(Intent(this, HomeActivity::class.java))
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
