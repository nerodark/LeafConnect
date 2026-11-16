package leafconnect

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.leafconnect.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class HomeActivity : AppCompatActivity() {

    private lateinit var session: NissanConnectSession
    private lateinit var overlay: View
    private lateinit var btnCancel: MaterialButton
    /** Root view the Snackbars (command results, cancel, watchdog notices) anchor to. */
    private lateinit var root: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var container: LinearLayout
    private lateinit var cmdStatus: TextView

    /**
     * The current vehicle list's status views + per-vehicle cached temp
     * records, so a CANCEL mid-fetch can restore each card's pre-fetch
     * state — "Refreshing..." must not linger after a cancel.
     */
    private var statusEntries: List<Triple<String, JSONObject?, StatusViews>> = emptyList()

    // Incremented on every loadVehicles() so a stale fetch thread never writes
    // into a freshly rebuilt UI (e.g. a swipe refresh that superseded it).
    @Volatile
    private var fetchGeneration = 0

    // Re-auth phase round token: bumped on cancel/new round so a stale
    // re-auth thread can't start a fetch (or re-show the overlay) afterward.
    @Volatile
    private var refreshId = 0

    // Cancellation: bumping cancelGeneration invalidates every in-flight work
    // round (refresh AND vehicle actions).
    @Volatile
    private var cancelGeneration = 0

    /**
     * In-flight per-request cancellation tokens. The old code kept Thread
     * references and called interrupt(): interrupt does NOT abort a blocking
     * socket read, so a stalled request ignored it. Every request now runs on
     * the executor and is canceled through CancellationToken, which cancels
     * the OkHttp Call — that aborts the socket read for real.
     */
    private val activeTokens = CopyOnWriteArrayList<CancellationToken>()

    /**
     * One worker thread for every fetch and command. Live battery status is
     * fetched sequentially, one vehicle at a time, so soft-login recovery never
     * races itself on the shared token/cookie state (concurrent 401s caused a
     * stale token to be used and a spurious onUnauthorized).
     */
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * All UI writes and the logout handoff go through this handler, so the
     * worker thread never touches views directly. Removed wholesale in
     * onDestroy — the original app left watchdogs queued on a dead activity.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    // Watchdog runnables of the active swipe round (removed on completion,
    // cancel, or when superseded).
    private var slowRunnable: Runnable? = null
    private var hardRunnable: Runnable? = null

    // One-shot watchdog for the INITIAL fetch only; removed the moment any
    // overlay is hidden so it can never fire during a later action.
    private var initialWatchdog: Runnable? = null

    // Splash overlay fade handler, tracked so onDestroy can drop its callback.
    private val splashHandler = Handler(Looper.getMainLooper())

    /**
     * Guard on the ONE logout path. The manual LOGOUT button, the 401
     * onUnauthorized callback and the client's onSessionDead all funnel through
     * logOut, and only the first call may open the login screen — the original
     * code could stack a second login screen on top of Home.
     */
    @Volatile
    private var logoutStarted = false

    // Per-vehicle status views: first = Battery/Temp row (with the M3 icon
    // button refresh), second = Plugin/Range rows (full width).
    private data class StatusViews(val first: TextView, val second: TextView, val refresh: MaterialButton)

    /** Density-scaled pixels for the views built in code (layouts use dp). */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        createContent()
    }

    // The CANCEL button's loading icon: an indeterminate ring drawn as the
    // button icon while a fetch runs. It drives its own rotation with a
    // [ValueAnimator] (start/stop below), so it spins on demand without
    // depending on the view it is attached to. Drawn in white; MaterialButton
    // tints it with the button's own icon tint (colorOnPrimary), so the ring
    // matches the button text in both day and night themes.
    private val spinnerIcon: SpinnerRing by lazy { SpinnerRing(this) }

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
            splashHandler.postDelayed({
                splashOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { splashOverlay.visibility = View.GONE }
                    .start()
            }, 1_000)
        }

        overlay = findViewById(R.id.overlay_home)
        root = findViewById(R.id.root_home)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        container = findViewById(R.id.container_home)
        cmdStatus = findViewById(R.id.cmd_status)

        // Swipe spinner in the scheme's hues instead of the AppCompat defaults.
        swipeRefresh.setColorSchemeResources(R.color.primary, R.color.secondary, R.color.tertiary)

        session = NissanConnectSession(this)
        session.onUnauthorized = {
            // 401 from a request that did not self-heal. One guarded path.
            mainHandler.post { logOut(manual = false) }
        }

        findViewById<MaterialButton>(R.id.btn_log_out).setOnClickListener { logOut() }

        // Only the CANCEL button cancels the in-progress fetch (tapping the
        // overlay just absorbs the touch so nothing behind it is tappable).
        overlay.setOnClickListener {}
        btnCancel = findViewById(R.id.btn_cancel)
        // The ring drawn as the button icon while the overlay is up.
        btnCancel.iconSize = dp(20)
        btnCancel.setOnClickListener { cancelRefresh() }

        swipeRefresh.setOnRefreshListener { refreshStatus() }

        // Check if a session exists (no network call). The gate is the session
        // token — present on every successful login, independent of whether the
        // credentials are kept (Keep-credentials off wipes the email but the
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
            // Removed as soon as any overlay is hidden (see hideOverlay), so
            // it can never fire during a later action.
            val watchdog = Runnable {
                if (overlay.isVisible) {
                    hideOverlay()
                    showMessage(R.string.still_loading)
                }
            }
            initialWatchdog = watchdog
            mainHandler.postDelayed(watchdog, 180_000)
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

    /**
     * Cleanup the original app had none of: watchdog callbacks, queued UI
     * writes, in-flight requests and the worker thread must all be released
     * when the activity is destroyed, otherwise they write into a dead
     * activity's views and keep running against the backend.
     */
    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        splashHandler.removeCallbacksAndMessages(null)
        activeTokens.forEach { it.cancel() }
        activeTokens.clear()
        ioExecutor.shutdown()
        try {
            ioExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!ioExecutor.isTerminated) ioExecutor.shutdownNow()
        super.onDestroy()
    }

    // One full refresh round: live battery/temp status. Shared by the
    // swipe-down gesture and the round-arrow refresh button next to the
    // status values.
    // Fetch-first: NO pre-login — the current token is used as-is, and the
    // fetch loop re-authenticates only if the server answers 401 (and the
    // vehicle list is re-synced only in that case).
    private fun refreshStatus() {
        // Show the loading overlay immediately and keep it up for the WHOLE
        // round, so the buttons stay blocked and the UI always says "fetch
        // in progress". The overlay goes down when the data arrives — or
        // when the user cancels (CANCEL).
        showOverlay()
        // A new round supersedes the previous one: drop its watchdogs
        // FIRST, otherwise the stale 180 s "Connection is slow" toast
        // (and 480 s "Still loading") fires out of nowhere — even after
        // the new round already finished.
        removeRoundWatchdogs()
        ++refreshId
        // A refresh starts a new round: the previous command's result banner
        // is stale the moment the user asks for fresh status, so clear it
        // (it is re-shown if the user sends a new command).
        cmdStatus.visibility = View.GONE
        // Soft watchdog: the round is abnormally slow — tell the user, but
        // keep the overlay up (it is the "fetch in progress" indicator).
        val slow = Runnable {
            showMessage(R.string.connection_slow)
        }
        slowRunnable = slow
        mainHandler.postDelayed(slow, 180_000)
        // Hard watchdog: last-resort release so the overlay can never block
        // the screen forever (worst case round: re-auth retry + slow
        // battery endpoint + socket retry can run many minutes).
        val hard = Runnable {
            hideOverlay()
            showMessage(R.string.still_loading)
        }
        hardRunnable = hard
        mainHandler.postDelayed(hard, 480_000)
        swipeRefresh.isRefreshing = false
        // The fetch itself (and its 401 self-healing) runs inside
        // loadVehicles.
        loadVehicles(null, true)
    }

    private fun removeRoundWatchdogs() {
        slowRunnable?.let { mainHandler.removeCallbacks(it) }
        hardRunnable?.let { mainHandler.removeCallbacks(it) }
        slowRunnable = null
        hardRunnable = null
    }

    private fun loadVehicles(initialBattery: JSONObject?, showLoading: Boolean) {
        val prefs = SecurePreferences.getInstance(this)
        // Early exit: never leave the swipe overlay up.
        val vehiclesStr = prefs.getString("vehicles") ?: run { hideOverlay(); return }
        val vehicles = JSONArray(vehiclesStr)
        container.removeAllViews()

        val entries = mutableListOf<Triple<String, JSONObject?, StatusViews>>()

        for (i in 0 until vehicles.length()) {
            val v = vehicles.getJSONObject(i)
            val vin = v.optString("uvi", "")
            val nickname = v.optString("nickname", "")
            val modelname = v.optString("modelname", "")

            // Manufacturer + model + model year come from the offline VIN decode
            // (the backend's modelyear field is wrong); the nickname stays in
            // parentheses.
            val vehicleName = vehicleName(vin, modelname, nickname)

            // One M3 outlined card per vehicle. The screen IS colorSurface, so
            // an outlined card reads as the screen with a hairline outline —
            // a filled/elevated card would look like a gray plate on white.
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (i > 0) topMargin = dp(12) }
            }
            // The card's content padding lives on the inner row: MaterialCardView
            // pads its own background, not the child, so text would hit the outline.
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            card.addView(body)

            // Vehicle name header: name on the first line, VIN on the second.
            // Both wrap to further lines if too wide for the screen.
            body.addView(TextView(this).apply {
                text = vehicleName
                setTextAppearance(R.style.TextAppearance_LeafConnect_VehicleName)
            })
            body.addView(TextView(this).apply {
                text = vin
                setTextAppearance(R.style.TextAppearance_LeafConnect_Vin)
                setPadding(0, dp(2), 0, dp(8))
            })

            // Live status (SOC / Temp / Range). When the data came from the
            // login flow it is already fresh; otherwise the live fetch
            // updates the text when it returns.
            val tempRecords = v.optJSONObject("interiorTempRecords")
            // Status block: Battery/Temp share a row with the refresh button
            // (same round as the swipe-down refresh); Plugin/Range stay on
            // their own full-width rows below.
            // While the live fetch is in progress the card shows the loading
            // state: the vehicle prefs only carry the temp forward, so the
            // partial cache would show "Temp" alone with the rest cleared.
            val (firstText, secondText) = if (showLoading) {
                Pair(getString(R.string.refreshing), "")
            } else {
                formatStatusParts(initialBattery?.optJSONObject(vin), tempRecords)
            }
            val firstView = TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_LeafConnect_StatusPrimary)
                text = firstText
            }
            val secondView = TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_LeafConnect_StatusSecondary)
                setPadding(0, 0, 0, dp(8))
                text = secondText
                visibility = if (secondText.isEmpty()) View.GONE else View.VISIBLE
            }
            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // M3 icon button: transparent container, ripple, 48dp touch target.
            // Inflated, not constructed: MaterialButton (1.13) has no defStyleRes
            // constructor, so a code-built button would render flat and unstyled.
            val refreshBtn = layoutInflater
                .inflate(R.layout.button_refresh, statusRow, false) as MaterialButton
            refreshBtn.contentDescription = getString(R.string.refresh_status)
            refreshBtn.setOnClickListener { refreshStatus() }
            // The arrow is the "refresh available" indicator: hidden
            // while this vehicle's own fetch is in flight (the card shows
            // "Refreshing..."), back when the data arrives.
            refreshBtn.visibility = if (showLoading) View.GONE else View.VISIBLE
            // WRAP_CONTENT (no weight): the text takes only what it needs,
            // so the refresh button sits right next to it — NOT pushed to
            // the far edge of the screen.
            statusRow.addView(firstView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            statusRow.addView(refreshBtn)
            val statusBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            statusBlock.addView(statusRow)
            statusBlock.addView(secondView)
            body.addView(statusBlock)
            entries.add(Triple(vin, tempRecords, StatusViews(firstView, secondView, refreshBtn)))

            // Door lock row — above the temp row, matching the original app
            // (lock / unlock send a default all-zero PIN authorization key).
            val lockRow = commandRow()
            addVehicleButton(lockRow, getString(R.string.lock)) { sendDoorCommand(vin, true) }
            addVehicleButton(lockRow, getString(R.string.unlock)) { sendDoorCommand(vin, false) }
            body.addView(lockRow)

            // Button row
            val buttonRow = commandRow()

            addVehicleButton(buttonRow, getString(R.string.start_temp)) {
                val token = newToken()
                vehicleAction(vin, getString(R.string.start_temp), token) { client ->
                    val ok = client.requestClimateControlOn(token)
                    if (!token.isCancelled) {
                        setCommandStatus(getString(R.string.start_temp), ok)
                        showMessage(if (ok) R.string.temperature_control_started
                        else R.string.temperature_control_start_failed)
                    }
                }
            }
            addVehicleButton(buttonRow, getString(R.string.stop_temp)) {
                val token = newToken()
                vehicleAction(vin, getString(R.string.stop_temp), token) { client ->
                    val ok = client.requestClimateControlOff(token)
                    if (!token.isCancelled) {
                        setCommandStatus(getString(R.string.stop_temp), ok)
                        showMessage(if (ok) R.string.temperature_control_stopped
                        else R.string.temperature_control_stop_failed)
                    }
                }
            }

            body.addView(buttonRow)

            // Charging + locate row — below temp. Mirrors the original
            // app's remoteChargingRequest; Locate sits next to Start charge.
            val chargeRow = commandRow()
            addVehicleButton(chargeRow, getString(R.string.start_charge)) {
                val token = newToken()
                vehicleAction(vin, getString(R.string.start_charge), token) { client ->
                    val ok = client.requestChargingStart(token)
                    if (!token.isCancelled) {
                        setCommandStatus(getString(R.string.start_charge), ok)
                        showMessage(if (ok) R.string.charging_started else R.string.charging_start_failed)
                    }
                }
            }
            addVehicleButton(chargeRow, getString(R.string.locate)) { locateVehicle(vin) }
            body.addView(chargeRow)
            container.addView(card)
        }

        // No vehicles on the account: a centered placeholder instead of a
        // blank page (the LOGOUT button stays pinned below).
        if (vehicles.length() == 0) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_vehicles)
                setTextAppearance(R.style.TextAppearance_LeafConnect_Placeholder)
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            }
            container.addView(empty)
        }

        if (initialBattery != null) return

        // Remember this round's status views (with the per-vehicle cached
        // temp records) so a CANCEL mid-fetch can restore each card's
        // pre-fetch state.
        statusEntries = entries.toList()

        // No live data yet: fetch battery status sequentially — one vehicle at
        // a time on the single worker thread — so soft-login recovery never
        // races itself on the shared token/cookie state.
        if (showLoading) showOverlay()
        val gen = ++fetchGeneration
        val loginGen = (prefs.getString("login_gen") ?: "0").toLong()
        val token = newToken()
        ioExecutor.submit {
            try {
                // 401 self-heal lives in NissanConnectClient (central path):
                // re-authenticate once and retry; if re-auth fails the
                // session is dead → onSessionDead logs out. No pre-login —
                // the current token is used as-is until the server says it
                // is expired.
                for ((vin, tempRecords, views) in entries) {
                    if (gen != fetchGeneration || token.isCancelled) break
                    try {
                        val client = clientWithDeathNotice(vin)
                        val bat = client.requestBatteryStatus(token)
                        if (logoutStarted) break
                        if (bat != null) {
                            NissanConnectCache.put(this, vin, bat.toString())
                            // Cache is now fresh for this login: no refetch until
                            // a newer login bumps login_gen.
                            prefs.put("cache_gen", loginGen.toString())
                        }
                        if (gen != fetchGeneration || token.isCancelled) continue
                        mainHandler.post {
                            val (firstText, secondText) = formatStatusParts(bat, tempRecords)
                            views.first.text = firstText
                            views.second.text = secondText
                            views.second.visibility = if (secondText.isEmpty()) View.GONE else View.VISIBLE
                            // The data arrived: the arrow is back.
                            views.refresh.visibility = View.VISIBLE
                        }
                    } catch (_: Exception) {
                        // The request died (network): the card must not stay
                        // on "Refreshing..." — restore its pre-fetch state.
                        if (gen == fetchGeneration && !token.isCancelled) {
                            mainHandler.post {
                                val (first, second) = formatStatusParts(null, tempRecords)
                                views.first.text = first
                                views.second.text = second
                                views.second.visibility = if (second.isEmpty()) View.GONE else View.VISIBLE
                                views.refresh.visibility = View.VISIBLE
                            }
                        }
                    }
                }
                // A round owns the watchdogs and the overlay only while it is
                // still the CURRENT round: a newer round (or a cancel /
                // logout) already dropped this round's watchdogs and owns the
                // overlay, so a superseded round must stay silent.
                if (gen == fetchGeneration) {
                    removeRoundWatchdogs()
                    mainHandler.post { hideOverlay() }
                }
            } finally {
                // The round owns its token: drop it so a finished round's
                // registered OkHttp calls are not retained by activeTokens.
                activeTokens.remove(token)
            }
        }
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

    // Split status: first = Battery/Temp rows (share a row with the refresh
    // arrow), second = Plugin/Range rows (stay full width). Every label comes
    // from strings.xml — the format strings are translatable.
    private fun formatStatusParts(bat: JSONObject?, fallbackTempRecords: JSONObject?): Pair<String, String> {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        // The payload is metric — kilometres and Celsius — so a United States
        // session converts both. Read the region once per render.
        val prefs = SecurePreferences.getInstance(this)
        val miles = AppLocale.usesMiles(prefs)
        val fahrenheit = AppLocale.usesFahrenheit(prefs)
        if (bat != null) {
            val records = bat.optJSONObject("batteryRecords")
            val soc = records?.optJSONObject("batteryStatus")?.optJSONObject("soc")?.optDouble("value", -1.0) ?: -1.0
            if (soc >= 0) first.add(getString(R.string.battery_status, soc.toInt()))
            val temp = bat.optJSONObject("temperatureRecords")?.optString("inc_temp", "") ?: ""
            if (temp.isNotEmpty()) first.add(formatTemp(temp, fahrenheit))
            val plugin = records?.optString("pluginState", "") ?: ""
            if (plugin.isNotEmpty()) second.add(getString(R.string.plugin_status, plugin))
            val rangeOn = records?.optDouble("cruisingRangeAcOn", -1.0) ?: -1.0
            val rangeOff = records?.optDouble("cruisingRangeAcOff", -1.0) ?: -1.0
            if (rangeOn >= 0) {
                // The official client switches between its KILOMETRE RANGE and
                // MILE RANGE labels the same way.
                val unit = getString(if (miles) R.string.unit_miles else R.string.unit_km)
                val on = if (miles) AppLocale.milesFromKm(rangeOn).roundToInt() else rangeOn.toInt()
                val off = if (miles) AppLocale.milesFromKm(rangeOff).roundToInt() else rangeOff.toInt()
                second.add(getString(R.string.range_status, on, off, unit))
            }
        }
        if (first.isEmpty() && fallbackTempRecords != null) {
            first.add(formatTemp(fallbackTempRecords.optDouble("inc_temp", 0.0).toString(), fahrenheit))
        }
        if (first.isEmpty()) first.add(getString(R.string.status_placeholder))
        return Pair(first.joinToString("\n"), second.joinToString("\n"))
    }

    /**
     * `inc_temp` is Celsius; a United States session renders Fahrenheit.
     * A payload that is not a number keeps its own text rather than
     * rendering a converted zero.
     */
    private fun formatTemp(celsiusRaw: String, fahrenheit: Boolean): String {
        val unit = getString(if (fahrenheit) R.string.unit_fahrenheit else R.string.unit_celsius)
        val celsius = celsiusRaw.toDoubleOrNull()
        val value = if (fahrenheit && celsius != null) {
            AppLocale.formatOneDecimal(AppLocale.fahrenheitFromC(celsius))
        } else {
            celsiusRaw
        }
        return getString(R.string.temp_status, value, unit)
    }

    /** A horizontal row of command buttons inside a vehicle card. */
    private fun commandRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun addVehicleButton(row: LinearLayout, label: String, action: () -> Unit) {
        // Filled button, the same style as every other button in the app. The
        // layout file carries the style and the equal weight (see the note in
        // button_command.xml: a constructed MaterialButton cannot take a style).
        val btn = layoutInflater
            .inflate(R.layout.button_command, row, false) as MaterialButton
        btn.text = label
        btn.setOnClickListener { action() }
        row.addView(btn)
    }

    /**
     * Lock / unlock. The door endpoints take a 5-digit authorization key;
     * the user is not asked to type one — a default all-zero PIN is sent
     * (mirroring the original app, which hardcoded "00000").
     */
    private fun sendDoorCommand(vin: String, lock: Boolean) {
        val prefs = SecurePreferences.getInstance(this)
        val accountId = prefs.getString("accountId") ?: ""
        if (accountId.isEmpty()) {
            showMessage(R.string.account_id_missing)
            return
        }
        val label = getString(if (lock) R.string.lock else R.string.unlock)
        val token = newToken()
        vehicleAction(vin, label, token) { client ->
            val ok = if (lock) client.requestDoorLock(accountId, token)
            else client.requestDoorUnlock(accountId, token)
            if (!token.isCancelled) {
                setCommandStatus(label, ok)
                showMessage(if (lock) {
                    if (ok) R.string.lock_command_sent else R.string.lock_failed
                } else {
                    if (ok) R.string.unlock_command_sent else R.string.unlock_failed
                })
            }
        }
    }

    // Locate the vehicle: fetch its last reported position and open Maps
    // centered on it. Mirrors the original app, which reads
    // sandsNotificationEvent.sandsNotificationEvent.body.location from the
    // refreshVehicleLocator response and drops a marker at (lat, lon).
    //
    // The API's location fields are strings holding decimal degrees:
    // latitudeDMS/longitudeDMS carry long decimal strings like
    // "12.3456789" (observed live on a 2024 Leaf; there are no numeric
    // latitude/longitude fields). The strings go into the geo: URI as-is;
    // only non-numeric values are rejected so a bad response shows
    // "Location not available" instead of a broken map.
    private fun locateVehicle(vin: String) {
        val label = getString(R.string.locate)
        val token = newToken()
        vehicleAction(vin, label, token) { client ->
            val body = client.requestLocation(token)
            val location = body?.optJSONObject("sandsNotificationEvent")
                ?.optJSONObject("sandsNotificationEvent")
                ?.optJSONObject("body")
                ?.optJSONObject("location")
            val lat = decimalField(location, "latitudeDMS")
            val lon = decimalField(location, "longitudeDMS")
            mainHandler.post {
                // A canceled round must not report "Location not available"
                // (the user just pressed CANCEL) nor open the map.
                if (token.isCancelled) return@post
                if (lat == null || lon == null) {
                    setCommandStatus(label, false)
                    showMessage(R.string.location_not_available)
                } else {
                    setCommandStatus(label, true)
                    openInMaps(lat, lon)
                }
            }
        }
    }

    /** Raw coordinate string, accepted only when it is a finite decimal number. */
    private fun decimalField(location: JSONObject?, key: String): String? =
        location?.optString(key)?.trim()?.takeIf { it.toDoubleOrNull()?.isFinite() == true }

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
                showMessage(R.string.no_maps_app)
            }
        }
    }

    companion object {
        // Set by a manual LOGOUT so the LoginActivity it opens does NOT fire
        // auto-login even if the preference write raced (the logout also
        // unchecks the preference, so the checkbox lands unchecked). Consumed
        // once by LoginActivity; a cold app start has it false, so auto-login
        // triggers there as normal.
        var justLoggedOut = false
    }

    /** A NissanConnectClient whose 401 self-heal failure logs this session out. */
    private fun clientWithDeathNotice(vin: String): NissanConnectClient {
        val client = NissanConnectClient(session, vin)
        client.onSessionDead = { mainHandler.post { logOut(manual = false) } }
        return client
    }

    private fun newToken(): CancellationToken {
        val t = CancellationToken()
        activeTokens.add(t)
        return t
    }

    /**
     * One command on the worker thread. The result is reported only when the
     * token was never canceled (a canceled socket read ends as an IOException
     * and must not toast "failed").
     */
    private fun vehicleAction(vin: String, label: String, token: CancellationToken, block: (NissanConnectClient) -> Unit) {
        val myCancel = cancelGeneration
        // A new action starts with a clean status line: the previous
        // command's banner is cleared before the loading spinner comes up.
        mainHandler.post { cmdStatus.visibility = View.GONE }
        showOverlay()
        ioExecutor.submit {
            try {
                // 401 self-heal lives in NissanConnectClient (central path):
                // one re-authenticate + one retry; if re-auth fails the
                // session is dead → onSessionDead logs out to the login
                // screen.
                val vehicle = clientWithDeathNotice(vin)
                try {
                    block(vehicle)
                } catch (e: Exception) {
                    // Only report if this action was not canceled in the
                    // meantime (an aborted socket read is the expected
                    // "error" when the user cancels).
                    if (myCancel == cancelGeneration && !token.isCancelled) {
                        setCommandStatus(label, false)
                        showMessage(e.message ?: "")
                    }
                }
            } catch (_: Exception) {
                // The client itself failed (network) — already reported above.
            }
            if (myCancel == cancelGeneration && !token.isCancelled) {
                mainHandler.post { hideOverlay() }
            }
            // The action owns its token: drop it so a finished action's
            // registered OkHttp calls are not retained by activeTokens.
            activeTokens.remove(token)
        }
    }

    // Records the last requested command + outcome in the status line on top
    // of the vehicle list — an M3 container in the success / error colors.
    // The container + foreground are color resources (banner_ok_*,
    // banner_fail_*), so dark mode gets its own pair instead of the
    // light-mode tints.
    private fun setCommandStatus(label: String, ok: Boolean) {
        mainHandler.post {
            cmdStatus.text = getString(R.string.cmd_status, label,
                getString(if (ok) R.string.cmd_success else R.string.cmd_failed))
            cmdStatus.visibility = View.VISIBLE
            val banner = android.graphics.drawable.GradientDrawable()
            banner.cornerRadius = dp(12).toFloat()
            banner.setColor(ContextCompat.getColor(this,
                if (ok) R.color.banner_ok_bg else R.color.banner_fail_bg))
            cmdStatus.background = banner
            cmdStatus.setPadding(
                dp(16), dp(10),
                dp(16), dp(10)
            )
            cmdStatus.setTextColor(ContextCompat.getColor(this,
                if (ok) R.color.banner_ok_fg else R.color.banner_fail_fg))
        }
    }

    /**
     * The ONE logout path: manual LOGOUT, 401 onUnauthorized and the client's
     * onSessionDead all funnel here, and the logoutStarted guard makes the
     * handoff to the login screen fire exactly once.
     */
    private fun logOut(manual: Boolean = true) {
        if (logoutStarted) return
        logoutStarted = true
        val prefs = SecurePreferences.getInstance(this)
        // A manual logout also UNCHECKS auto-login: the user opted out of
        // silent re-login, and the login screen shows the box unchecked.
        // justLoggedOut additionally pins the form open even if a preference
        // or token raced. An expiry-triggered logout keeps auto-login armed
        // (rejected credentials are already disarmed by reauthenticate).
        if (manual) {
            justLoggedOut = true
            prefs.put("auto_login", "0")
        }
        // Keep credentials: the email (and the encrypted password) survive
        // the logout (the form is pre-filled next time) unless the user opted
        // out of keeping them.
        val keepCreds = (prefs.getString("keep_credentials") ?: "0") == "1"
        // Session state only. keep_credentials / email / password are user
        // preferences and stay as the user set them (auto_login is unchecked
        // above when the logout was manual).
        for (key in listOf("token", "vehicles", "accountId")) {
            prefs.remove(key)
        }
        session.clearSession()  // token + the whole cookie set
        if (!keepCreds) {
            prefs.remove("email")
            prefs.remove("password")
            prefs.remove("keep_credentials")
        }
        if (manual) {
            // A manual logout drops the in-memory password too.
            CredentialStore.clear()
        }
        NissanConnectCache.clear(this)
        // Stale the cache marker: the next login must fetch fresh data.
        prefs.put("cache_gen", "0")
        // Stop any in-flight work (fetch or vehicle action) from writing
        // into the UI we're leaving.
        fetchGeneration++
        refreshId++
        cancelGeneration++
        activeTokens.forEach { it.cancel() }
        removeRoundWatchdogs()
        initialWatchdog?.let { mainHandler.removeCallbacks(it) }
        initialWatchdog = null
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    // Cancel the in-progress work: invalidate every active round (refresh AND
    // vehicle actions), cancel their OkHttp calls so a blocking socket read
    // ends promptly, and release the overlay so the buttons can be used right
    // away. Any result that arrives afterward is discarded (stale generation),
    // so a canceled LOCATE can't open the map later.
    private fun cancelRefresh() {
        cancelGeneration++
        refreshId++
        fetchGeneration++
        activeTokens.forEach { it.cancel() }
        removeRoundWatchdogs()
        initialWatchdog?.let { mainHandler.removeCallbacks(it) }
        initialWatchdog = null
        // A canceled round never reaches its status views: restore every
        // card still showing "Refreshing..." to its pre-fetch state (a late
        // cancel may have let earlier cards finish with real data — those
        // already show their values and are left as-is).
        for ((_, tempRecords, views) in statusEntries) {
            if (views.first.text.toString() == getString(R.string.refreshing)) {
                val (first, second) = formatStatusParts(null, tempRecords)
                views.first.text = first
                views.second.text = second
                views.second.visibility = if (second.isEmpty()) View.GONE else View.VISIBLE
                views.refresh.visibility = View.VISIBLE
            }
        }
        hideOverlay()
        swipeRefresh.isRefreshing = false
        showMessage(R.string.canceled)
    }

    private fun showOverlay() {
        mainHandler.post {
            overlay.visibility = View.VISIBLE
            // The spinner lives inside the CANCEL button (its icon), not as a
            // separate overlay child — the "in progress" state is the button.
            btnCancel.setIcon(spinnerIcon)
            spinnerIcon.start()
        }
    }

    private fun hideOverlay() {
        mainHandler.post {
            overlay.visibility = View.GONE
            btnCancel.setIcon(null)
            spinnerIcon.stop()
            // Any overlay release retires the initial-fetch watchdog.
            initialWatchdog?.let { mainHandler.removeCallbacks(it) }
            initialWatchdog = null
        }
    }

    /**
     * Transient M3 feedback (Snackbar). Posted to the UI thread because worker
     * threads report command results and watchdog notices.
     */
    private fun showMessage(res: Int) {
        mainHandler.post { Snackbar.make(root, getString(res), Snackbar.LENGTH_LONG).show() }
    }

    private fun showMessage(text: String) {
        mainHandler.post { Snackbar.make(root, text, Snackbar.LENGTH_LONG).show() }
    }
}

/**
 * Indeterminate ring shown as a [MaterialButton] icon while a fetch runs.
 *
 * Drives its own rotation with a [ValueAnimator] started/stopped by
 * [start]/[stop], so it spins on demand without depending on the view it is
 * attached to. Resolves colorOnPrimary from the current theme and draws with
 * it, so the ring matches the button text in both day (white) and night (dark)
 * themes.
 */
private class SpinnerRing(private val context: Context) : Drawable() {

    private val metrics = context.resources.displayMetrics
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * metrics.density
        color = ringColor(context)
    }
    private val arcRect = RectF()
    private val rotation = ValueAnimator.ofInt(0, 360).apply {
        duration = 1_100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        // Each frame, ask the host view to redraw so the arc's new angle shows.
        addUpdateListener { invalidateSelf() }
    }

    fun start() {
        // Re-resolve the theme color in case the theme changed since the ring
        // was first built (the ring is a lazy singleton reused across fetches).
        paint.color = ringColor(context)
        if (!rotation.isRunning) rotation.start()
    }

    fun stop() {
        if (rotation.isRunning) rotation.end()
    }

    override fun draw(abc: Canvas) {
        val b = bounds
        val stroke = paint.strokeWidth
        // Inset by half the stroke on every side so the arc is centered in the
        // bounds and the stroke's outer edge sits on the bounds edge (the old
        // (size-stroke)/2 inset shifted the arc into the bottom-right and let
        // it run past the bounds).
        val inset = stroke / 2f
        arcRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        // -90 so the arc starts at the 12 o'clock position; 270 sweep leaves the
        // characteristic "gap" of an indeterminate spinner.
        val angle = (rotation.animatedValue as Int).toFloat() - 90f
        abc.drawArc(arcRect, angle, 270f, false, paint)
    }

    override fun getIntrinsicWidth(): Int = (20 * metrics.density).toInt()

    override fun getIntrinsicHeight(): Int = (20 * metrics.density).toInt()

    override fun isStateful(): Boolean = false

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setTint(tint: Int) {
        paint.color = tint
    }

    override fun setTintMode(mode: PorterDuff.Mode?) {
        if (mode != null) paint.xfermode = PorterDuffXfermode(mode)
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    private companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** colorOnPrimary from the current theme; white as a fallback. */
        private fun ringColor(context: Context): Int {
            val a = context.obtainStyledAttributes(
                intArrayOf(com.google.android.material.R.attr.colorOnPrimary)
            )
            val c = a.getColor(0, WHITE)
            a.recycle()
            return c
        }
    }
}
