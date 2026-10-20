package leafconnect

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.leafconnect.R
import org.json.JSONArray
import org.json.JSONObject

class HomeActivity : AppCompatActivity() {

    private lateinit var session: NissanConnectSession
    private lateinit var overlay: FrameLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var container: LinearLayout
    private lateinit var cmdStatus: TextView

    // Incremented on every loadVehicles() so a stale fetch thread never writes
    // into a freshly rebuilt UI (e.g. a swipe refresh that superseded it).
    @Volatile
    private var fetchGeneration = 0

    // Re-auth phase round token: bumped on cancel/new round so a stale
    // re-auth thread can't start a fetch (or re-show the overlay) afterward.
    @Volatile
    private var refreshId = 0

    // Cancellation: bumping cancelGeneration invalidates every in-flight work
    // thread (refresh AND vehicle actions); activeThreads are the threads
    // that get interrupted so a blocking socket read ends promptly.
    @Volatile
    private var cancelGeneration = 0
    private val activeThreads = java.util.concurrent.CopyOnWriteArrayList<Thread>()

    // Watchdog runnables of the active swipe round (removed on completion,
    // cancel, or when superseded).
    private var slowRunnable: Runnable? = null
    private var hardRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        createContent()
    }

    private fun createContent() {
        setContentView(R.layout.activity_home)

        // Splash hold: the system splash is dismissed at the first frame, so a
        // white overlay with the icon keeps the splash look for 1s. Skipped
        // when we arrive from the login screen (the user just saw the app —
        // no second splash between login and home).
        val splashOverlay = findViewById<FrameLayout>(R.id.splash_overlay)
        if (intent?.getBooleanExtra("from_login", false) == true) {
            splashOverlay.visibility = View.GONE
        } else {
            Handler(Looper.getMainLooper()).postDelayed({
                splashOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { splashOverlay.visibility = View.GONE }
                    .start()
            }, 1_000)
        }

        overlay = findViewById(R.id.overlay_home)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        container = findViewById(R.id.container_home)
        cmdStatus = findViewById(R.id.cmd_status)

        session = NissanConnectSession(this)
        session.onUnauthorized = {
            runOnUiThread {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }

        findViewById<Button>(R.id.btn_log_out).setOnClickListener { logOut() }

        // Tap the overlay (or CANCEL) to cancel the in-progress fetch and get
        // the buttons back immediately.
        overlay.setOnClickListener { cancelRefresh() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { cancelRefresh() }

        swipeRefresh.setOnRefreshListener {
            // Show the loading overlay immediately — before the re-authenticate
            // round-trip — and keep it up for the WHOLE round, so the buttons
            // stay blocked and the UI always says "fetch in progress". The
            // overlay goes down when the data arrives — or when the user
            // cancels (tap the overlay / CANCEL).
            showOverlay()
            val myId = ++refreshId
            val myCancel = cancelGeneration
            // Soft watchdog: the round is abnormally slow — tell the user, but
            // keep the overlay up (it is the "fetch in progress" indicator).
            val slow = Runnable {
                android.widget.Toast.makeText(this, "Connection is slow — status will update when ready", android.widget.Toast.LENGTH_LONG).show()
            }
            slowRunnable = slow
            Handler(Looper.getMainLooper()).postDelayed(slow, 180_000)
            // Hard watchdog: last-resort release so the overlay can never block
            // the screen forever (worst case round: re-auth retries + slow
            // battery endpoint + socket retry can run many minutes).
            val hard = Runnable {
                hideOverlay()
                android.widget.Toast.makeText(this, "Still loading — status will update when ready", android.widget.Toast.LENGTH_LONG).show()
            }
            hardRunnable = hard
            Handler(Looper.getMainLooper()).postDelayed(hard, 480_000)
            val t = Thread {
                try {
                    // Never let re-authenticate kill the refresh: on any
                    // failure the battery status is still requested below.
                    val result = try {
                        session.reauthenticate()
                    } catch (_: Exception) {
                        "error"
                    }
                    runOnUiThread {
                        // This round was canceled (or superseded) — do nothing.
                        if (myId != refreshId || myCancel != cancelGeneration) return@runOnUiThread
                        Handler(Looper.getMainLooper()).removeCallbacks(slow)
                        Handler(Looper.getMainLooper()).removeCallbacks(hard)
                        swipeRefresh.isRefreshing = false
                        if (result == "expired") {
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        } else {
                            // Always request fresh battery status after a swipe
                            // (even when re-authenticate failed); the overlay
                            // stays up until the data arrives.
                            loadVehicles(null, true)
                        }
                    }
                } finally {
                    activeThreads.remove(Thread.currentThread())
                }
            }
            activeThreads.add(t)
            t.start()
        }

        // Check if a session exists (no network call). The gate is the session
        // token — present on every successful login, independent of whether the
        // credentials are kept (Keep-credentials off wipes email/password but the
        // token stays, so a fresh login must not bounce back to the login screen).
        val prefs = SecurePreferences.getInstance(this)
        if ((prefs.getString("token") ?: "").isNotEmpty()) {
            // The home screen appears immediately (vehicle cards, buttons,
            // placeholder status) and the live battery status is fetched behind
            // the loading overlay; the spinner stays up until the data arrives.
            //
            // When a cache exists (e.g. the activity was recreated by an
            // orientation change) the cached data is reused and NO refresh is
            // triggered.
            // Fetch live battery data ONLY when a login is newer than the
            // cached data (or no data is cached). Wake, rotation and task
            // restore carry no new login → rebuild from cache, NO fetch.
            val loginGen = (prefs.getString("login_gen") ?: "0").toLong()
            val cacheGen = (prefs.getString("cache_gen") ?: "0").toLong()
            val cached = NissanConnectCache.all(this)
            // Watchdog: if the initial live fetch takes abnormally long, release
            // the UI — the fetch keeps running and updates the text when done.
            // (No-op when the fetch already finished and hid the overlay.)
            Handler(Looper.getMainLooper()).postDelayed({
                if (overlay.isVisible) {
                    hideOverlay()
                    android.widget.Toast.makeText(this, "Still loading — status will update when ready", android.widget.Toast.LENGTH_LONG).show()
                }
            }, 180_000)
            if (loginGen > cacheGen || cached.length() == 0) {
                loadVehicles(null, true)
            } else {
                loadVehicles(cached, false)
            }
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun loadVehicles(initialBattery: JSONObject?, showLoading: Boolean) {
        val prefs = SecurePreferences.getInstance(this)
        // Early exit: never leave the swipe overlay up.
        val vehiclesStr = prefs.getString("vehicles") ?: run { hideOverlay(); return }
        val vehicles = JSONArray(vehiclesStr)
        container.removeAllViews()

        val entries = mutableListOf<Triple<String, JSONObject?, TextView>>()

        for (i in 0 until vehicles.length()) {
            val v = vehicles.getJSONObject(i)
            val vin = v.optString("uvi", "")
            val nickname = v.optString("nickname", "")
            val modelname = v.optString("modelname", "")

            // Manufacturer + model + model year come from the offline VIN decode
            // (the backend's modelyear field is wrong); the nickname stays in
            // parentheses.
            val vehicleName = vehicleName(vin, modelname, nickname)

            // Vehicle name header: name on the first line, VIN on the second.
            // Both wrap to further lines if too wide for the screen.
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 24, 0, 8)
            }
            nameRow.addView(TextView(this).apply {
                text = vehicleName
                textSize = 18f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            })
            nameRow.addView(TextView(this).apply {
                text = vin
                textSize = 13f
                setPadding(0, 2, 0, 0)
            })
            container.addView(nameRow)

            // Live status (SOC / Temp / Range). When the data came from the
            // login flow it is already fresh; otherwise placeholder until the
            // live fetch returns.
            val tempRecords = v.optJSONObject("interiorTempRecords")
            val infoView = TextView(this).apply {
                textSize = 14f
                setPadding(0, 0, 0, 8)
            }
            infoView.text = formatVehicleStatus(initialBattery?.optJSONObject(vin), tempRecords)
            container.addView(infoView)
            entries.add(Triple(vin, tempRecords, infoView))

            // Door lock row — above the temp row, matching the original app
            // (LOCK / UNLOCK with a PIN authorization key).
            val lockRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            addVehicleButton(lockRow, "LOCK") { sendDoorCommand(vin, true) }
            addVehicleButton(lockRow, "UNLOCK") { sendDoorCommand(vin, false) }
            container.addView(lockRow)

            // Button row
            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            addVehicleButton(buttonRow, "START TEMP") {
                vehicleAction(vin, "START TEMP") { v2 ->
                    val ok = v2.requestClimateControlOn()
                    setCommandStatus("START TEMP", ok)
                    runOnUiThread {
                        android.widget.Toast.makeText(this@HomeActivity, if (ok) "Temperature control started" else "Temperature control start failed", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            addVehicleButton(buttonRow, "STOP TEMP") {
                vehicleAction(vin, "STOP TEMP") { v2 ->
                    val ok = v2.requestClimateControlOff()
                    setCommandStatus("STOP TEMP", ok)
                    runOnUiThread {
                        android.widget.Toast.makeText(this@HomeActivity, if (ok) "Temperature control stopped" else "Temperature control stop failed", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }

            container.addView(buttonRow)

            // Charging + locate row — below temp. Mirrors the original
            // app's remoteChargingRequest; LOCATE sits next to START CHARGE.
            val chargeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            addVehicleButton(chargeRow, "START CHARGE") {
                vehicleAction(vin, "START CHARGE") { v2 ->
                    val ok = v2.requestChargingStart()
                    setCommandStatus("START CHARGE", ok)
                    runOnUiThread {
                        android.widget.Toast.makeText(this@HomeActivity, if (ok) "Charging started" else "Charging start failed", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            addVehicleButton(chargeRow, "LOCATE") { locateVehicle(vin) }
            container.addView(chargeRow)
        }

        if (initialBattery != null) return

        // No live data yet: fetch battery status sequentially — one vehicle at
        // a time on a single thread — so soft-login recovery never races itself
        // on the shared token/cookie state (concurrent 401s caused a stale token
        // to be used and a spurious onUnauthorized).
        if (showLoading) showOverlay()
        val gen = ++fetchGeneration
        val loginGen = (prefs.getString("login_gen") ?: "0").toLong()
        val fetchThread = Thread {
            try {
                for ((vin, tempRecords, infoView) in entries) {
                if (gen != fetchGeneration) return@Thread
                try {
                    val vehicle = NissanConnectClient(session, vin)
                    val bat = vehicle.requestBatteryStatus()
                    if (bat != null) {
                        NissanConnectCache.put(this, vin, bat.toString())
                        // Cache is now fresh for this login: no refetch until
                        // a newer login bumps login_gen.
                        prefs.put("cache_gen", loginGen.toString())
                    }
                    if (gen != fetchGeneration) continue
                    runOnUiThread {
                        infoView.text = formatVehicleStatus(bat, tempRecords)
                    }
                } catch (_: Exception) {
                }
            }
            if (showLoading) {
                runOnUiThread { hideOverlay() }
            } else {
                // Quiet background fetch (post-watchdog) finished: stop the
                // "fetch in progress" spinner and confirm to the user.
                runOnUiThread {
                    swipeRefresh.isRefreshing = false
                    android.widget.Toast.makeText(this, "Status updated", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            } finally {
                activeThreads.remove(Thread.currentThread())
            }
        }
        activeThreads.add(fetchThread)
        fetchThread.start()
    }

    // The header shows manufacturer + model + model year from the offline VIN
    // decode (the backend's modelyear field is unreliable — it reports 2018 for
    // this 2024 Leaf). The user's nickname is kept in parentheses.
    private fun vehicleName(vin: String, modelname: String, nickname: String): String {
        val info = (VinDecoder.decode(vin) as? VinResult.Success)?.info
        val parts = mutableListOf<String>()
        info?.manufacturer?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (modelname.isNotEmpty()) parts.add(modelname)
        info?.modelYear?.let { parts.add(it.toString()) }
        val base = parts.joinToString().replace(",", " ").uppercase()
        return if (nickname.isNotEmpty()) "$base ($nickname)" else base
    }

    private fun formatVehicleStatus(bat: JSONObject?, fallbackTempRecords: JSONObject?): String {
        val lines = mutableListOf<String>()
        if (bat != null) {
            val records = bat.optJSONObject("batteryRecords")
            val soc = records?.optJSONObject("batteryStatus")?.optJSONObject("soc")?.optDouble("value", -1.0) ?: -1.0
            if (soc >= 0) lines.add("Battery: ${soc.toInt()}%")
            val temp = bat.optJSONObject("temperatureRecords")?.optString("inc_temp", "") ?: ""
            if (temp.isNotEmpty()) lines.add("Temp: $temp°C")
            val plugin = records?.optString("pluginState", "") ?: ""
            if (plugin.isNotEmpty()) lines.add("Plugin: $plugin")
            val rangeOn = records?.optDouble("cruisingRangeAcOn", -1.0) ?: -1.0
            val rangeOff = records?.optDouble("cruisingRangeAcOff", -1.0) ?: -1.0
            if (rangeOn >= 0) lines.add("Range: ${rangeOn.toInt()} km (HVAC on) / ${rangeOff.toInt()} km (HVAC off)")
        }
        if (lines.isEmpty() && fallbackTempRecords != null) {
            lines.add("Temp: ${fallbackTempRecords.optDouble("inc_temp", 0.0)}°C")
        }
        if (lines.isEmpty()) lines.add("Status: --")
        return lines.joinToString("\n")
    }

    private fun addVehicleButton(row: LinearLayout, label: String, action: () -> Unit) {
        val btn = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        row.addView(btn)
    }

    // Door lock/unlock. The original app sends a user PIN as the
    // "authorizationKey"; we always send the default value.
    private fun sendDoorCommand(vin: String, lock: Boolean) {
        val prefs = SecurePreferences.getInstance(this)
        val accountId = prefs.getString("accountId") ?: ""
        if (accountId.isEmpty()) {
            android.widget.Toast.makeText(this, "Account ID missing — log out and back in", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        vehicleAction(vin, if (lock) "LOCK" else "UNLOCK") { v2 ->
            val ok = if (lock) v2.requestDoorLock(DEFAULT_PIN, accountId) else v2.requestDoorUnlock(DEFAULT_PIN, accountId)
            setCommandStatus(if (lock) "LOCK" else "UNLOCK", ok)
            runOnUiThread {
                android.widget.Toast.makeText(this@HomeActivity, if (ok) (if (lock) "Lock command sent" else "Unlock command sent") else (if (lock) "Lock failed" else "Unlock failed"), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Locate the vehicle: fetch its last reported position and open Google
    // Maps centered on it. Mirrors the original app, which reads
    // sandsNotificationEvent.sandsNotificationEvent.body.location from the
    // refreshVehicleLocator response and drops a marker at (lat, lon).
    private fun locateVehicle(vin: String) {
        vehicleAction(vin, "LOCATE") { v2 ->
            val body = v2.requestLocation()
            val location = body?.optJSONObject("sandsNotificationEvent")
                ?.optJSONObject("sandsNotificationEvent")
                ?.optJSONObject("body")
                ?.optJSONObject("location")
            val lat = location?.optString("latitudeDMS")?.trim()
            val lon = location?.optString("longitudeDMS")?.trim()
            runOnUiThread {
                if (location == null || lat.isNullOrEmpty() || lon.isNullOrEmpty()) {
                    setCommandStatus("LOCATE", false)
                    android.widget.Toast.makeText(this@HomeActivity, "Location not available", android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                setCommandStatus("LOCATE", true)
                openInMaps(lat, lon)
            }
        }
    }

    private fun openInMaps(lat: String, lon: String) {
        val intent = Intent(Intent.ACTION_VIEW, "geo:$lat,$lon?q=$lat,$lon".toUri())
        intent.`package` = "com.google.android.apps.maps"
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Fall back to the browser if the Maps app is unavailable.
            val web = Intent(Intent.ACTION_VIEW, "https://maps.google.com/?q=$lat,$lon".toUri())
            try {
                startActivity(web)
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "No maps app available", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val DEFAULT_PIN = "00000"

        // Set by a manual LOGOUT so the LoginActivity it opens does NOT fire
        // auto-login (the preference stays checked/persisted). Consumed once
        // by LoginActivity; a cold app start has it false, so auto-login
        // triggers there as normal.
        var justLoggedOut = false
    }

    private fun vehicleAction(vin: String, label: String, block: (NissanConnectClient) -> Unit) {
        // A new action starts with a clean status line: the previous
        // command's banner is cleared before the loading spinner comes up.
        runOnUiThread { cmdStatus.visibility = View.GONE }
        showOverlay()
        val myCancel = cancelGeneration
        val t = Thread {
            try {
                try {
                    val vehicle = NissanConnectClient(session, vin)
                    block(vehicle)
                } catch (e: Exception) {
                    // Only report if this action wasn't canceled in the
                    // meantime (an interrupted socket read is the expected
                    // "error" when the user cancels).
                    if (myCancel == cancelGeneration) {
                        setCommandStatus(label, false)
                        runOnUiThread {
                            android.widget.Toast.makeText(this@HomeActivity, e.message, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } finally {
                activeThreads.remove(Thread.currentThread())
            }
            if (myCancel == cancelGeneration) {
                runOnUiThread { hideOverlay() }
            }
        }
        activeThreads.add(t)
        t.start()
    }

    // Records the last requested command + outcome in the status line on top
    // of the vehicle list — a light-green banner on success, light-red on
    // failure.
    private fun setCommandStatus(label: String, ok: Boolean) {
        runOnUiThread {
            cmdStatus.text = "Last command: $label — ${if (ok) "SUCCESS" else "FAILED"}"
            cmdStatus.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            val banner = android.graphics.drawable.GradientDrawable()
            banner.cornerRadius = 8 * density
            banner.setColor(if (ok) 0xFFC8E6C9.toInt() else 0xFFFFCDD2.toInt())
            cmdStatus.background = banner
            cmdStatus.setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt()
            )
            cmdStatus.setTextColor(if (ok) 0xFF1B5E20.toInt() else 0xFFC62828.toInt())
        }
    }

    private fun logOut() {
        val prefs = SecurePreferences.getInstance(this)
        // A manual logout must not immediately auto-login: signal the
        // LoginActivity we're about to open (the preference itself is left
        // checked and persisted).
        justLoggedOut = true
        // Keep credentials: the email/password survive the logout (the form
        // is pre-filled next time) unless the user opted out of keeping them.
        val keepCreds = (prefs.getString("keep_credentials") ?: "0") == "1"
        for (key in listOf("token", "cookies", "JsessionId", "vehicles", "accountId")) {
            prefs.remove(key)
        }
        if (!keepCreds) {
            prefs.remove("email")
            prefs.remove("password")
        }
        NissanConnectCache.clear(this)
        // Stale the cache marker: the next login must fetch fresh data.
        prefs.put("cache_gen", "0")
        // Stop any in-flight work (fetch or vehicle action) from writing
        // into the UI we're leaving.
        fetchGeneration++
        refreshId++
        cancelGeneration++
        activeThreads.toList().forEach { it.interrupt() }
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    // Cancel the in-progress work: invalidate every active thread (refresh
    // AND vehicle actions), interrupt them so a blocking socket read ends
    // promptly, and release the overlay so the buttons can be used right
    // away. Any result that arrives afterward is discarded (stale
    // generation), so a canceled LOCATE can't open the map later.
    private fun cancelRefresh() {
        cancelGeneration++
        refreshId++
        fetchGeneration++
        activeThreads.toList().forEach { it.interrupt() }
        slowRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        hardRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        hideOverlay()
        swipeRefresh.isRefreshing = false
        android.widget.Toast.makeText(this, "Canceled", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showOverlay() {
        runOnUiThread { overlay.visibility = View.VISIBLE }
    }

    private fun hideOverlay() {
        runOnUiThread { overlay.visibility = View.GONE }
    }
}
