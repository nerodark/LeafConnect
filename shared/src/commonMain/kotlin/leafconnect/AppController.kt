package leafconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Everything the two screens do, expressed as state flows the Compose UI
 * renders. This replaces LoginActivity/HomeActivity's Handler + executor
 * plumbing: rounds run as coroutines on a per-round Job, so cancelling a round
 * aborts its HTTP request for real, and generation counters keep a superseded
 * fetch from writing into a freshly rebuilt UI.
 */

enum class Screen { LOGIN, HOME }

/** Localizable messages; the UI resolves the key against composeResources. */
enum class ToastKey {
    CANCELED, CONNECTION_SLOW, STILL_LOADING, LOGIN_FAILED, EMAIL_REQUIRED, EMAIL_INVALID,
    PASSWORD_REQUIRED, CONNECTION_ERROR, NO_MAPS_APP, ACCOUNT_ID_MISSING, LOCATION_NOT_AVAILABLE,
    NO_VEHICLES, TEMP_STARTED, TEMP_START_FAILED, TEMP_STOPPED, TEMP_STOP_FAILED,
    CHARGE_STARTED, CHARGE_START_FAILED, LOCK_SENT, UNLOCK_SENT, LOCK_FAILED, UNLOCK_FAILED,
    RAW_MESSAGE,
}

/** Command labels for the banner line. */
enum class CommandKey { LOCK, UNLOCK, START_TEMP, STOP_TEMP, START_CHARGE, LOCATE }

data class Toast(val key: ToastKey, val argument: String? = null)

data class CommandBanner(val label: CommandKey, val ok: Boolean)

/** One vehicle card. `fetching` shows the loading label and hides the refresh arrow. */
data class VehicleUi(
    val vin: String,
    val name: String,
    val vinText: String,
    val status: VehicleStatus?,
    val fetching: Boolean,
)

class AppController(private val scope: CoroutineScope) {

    private val prefs: SettingsStore = prefsStore
    private val session = NissanConnectSession().apply {
        // A 401 that nobody self-healed ends on the login screen, once.
        onUnauthorized = { logout(manual = false) }
    }

    val screen = MutableStateFlow(Screen.LOGIN)
    val vehicles = MutableStateFlow<List<VehicleUi>>(emptyList())
    val loading = MutableStateFlow(false)
    val banner = MutableStateFlow<CommandBanner?>(null)
    val toast = MutableStateFlow<Toast?>(null)
    val splash = MutableStateFlow(true)

    /** True right after a login, so the first home fetch is announced as from-login. */
    var fromLogin = false
        private set

    private var logoutStarted = false
    private var justLoggedOut = false
    private var fetchGeneration = 0
    private var cancelGeneration = 0
    private val activeTokens = mutableListOf<CancellationToken>()
    private var slowWatchdog: Job? = null
    private var hardWatchdog: Job? = null

    companion object {
        /**
         * Email validation without Android's `Patterns` (that is JVM-only).
         * Accepts the addresses the backend actually uses.
         */
        val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }

    /** Email check shared by the login screen's inline error and login(). */
    fun isValidEmail(email: String): Boolean = EMAIL_PATTERN.matches(email.trim())

    // ---------------------------------------------------------------- start

    /** Decide the first screen: a stored session opens Home, otherwise Login. */
    fun start() {
        fadeSplash()
        if (!prefs.contains("token")) {
            screen.value = Screen.LOGIN
            if (autoLoginEnabled()) performAutoLogin()
            return
        }
        if (prefs.getString("vehicles") == null) {
            // A token with no vehicle list is a dead session: log out to Login.
            clearDeadSession()
            screen.value = Screen.LOGIN
            return
        }
        screen.value = Screen.HOME
        rebuildCards()
        if (loginGeneration() != cacheGeneration()) fetchAll(justLoggedIn = false)
    }

    private fun fadeSplash() {
        scope.launch {
            delay(1_000)
            splash.value = false
        }
    }

    private fun loginGeneration(): Long = (prefs.getString("login_gen") ?: "0").toLongOrNull() ?: 0
    private fun cacheGeneration(): Long = (prefs.getString("cache_gen") ?: "0").toLongOrNull() ?: 0

    private fun autoLoginEnabled(): Boolean =
        (prefs.getString("auto_login") ?: "0") == "1" && !justLoggedOut &&
            !prefs.contains("token") && initialEmail().isNotEmpty() && initialPassword().isNotEmpty()

    private fun performAutoLogin() {
        login(initialEmail(), initialPassword(), keepCredentials = true)
    }

    // ---------------------------------------------------------------- login

    /** Login screen values, from the encrypted store. */
    fun initialEmail(): String = prefs.getString("email") ?: ""

    /** RAM first, then the encrypted store — exactly as the original form. */
    fun initialPassword(): String =
        CredentialStore.password ?: prefs.getString("password") ?: ""

    fun keepCredentialsStored(): Boolean = (prefs.getString("keep_credentials") ?: "0") == "1"

    fun autoLoginStored(): Boolean = (prefs.getString("auto_login") ?: "0") == "1"

    /** The region the selector preselects: the stored one, or the device locale. */
    fun initialRegion(): String = AppLocale.sessionCountry(prefs)

    fun selectRegion(country: String) {
        prefs.put(AppLocale.PREF_COUNTRY, country)
    }

    /**
     * Checkbox rows persist the moment they are toggled (not on login), like the
     * original activity. Unchecking Keep credentials removes the stored email
     * AND password immediately; the on-screen fields stay as they are.
     */
    fun setKeepCredentials(on: Boolean) {
        prefs.put("keep_credentials", if (on) "1" else "0")
        if (!on) {
            prefs.remove("email")
            prefs.remove("password")
        }
    }

    fun setAutoLogin(on: Boolean) {
        prefs.put("auto_login", if (on) "1" else "0")
    }

    fun login(email: String, password: String, keepCredentials: Boolean) {
        // Validation mirrors the original login screen.
        if (email.isBlank()) {
            toast.value = Toast(ToastKey.EMAIL_REQUIRED)
            return
        }
        if (!EMAIL_PATTERN.matches(email.trim())) {
            toast.value = Toast(ToastKey.EMAIL_INVALID)
            return
        }
        if (password.isBlank()) {
            toast.value = Toast(ToastKey.PASSWORD_REQUIRED)
            return
        }

        prefs.put("keep_credentials", if (keepCredentials) "1" else "0")
        CredentialStore.set(email, password)
        prefs.put("email", email)
        if (keepCredentials) prefs.put("password", password) else prefs.remove("password")

        loading.value = true
        val token = newToken()
        val job = scope.launch {
            try {
                val body = session.login(email, password, token)
                applyLoginResult(body)
                justLoggedOut = false
                fromLogin = true
                banner.value = null
                screen.value = Screen.HOME
                rebuildCards()
                fetchAll(justLoggedIn = true)
            } catch (e: CancellationException) {
                // The screen went away or the user canceled: no error to show.
            } catch (e: Throwable) {
                if (!token.isCancelled) toast.value = Toast(ToastKey.LOGIN_FAILED)
            } finally {
                hideOverlay()
                activeTokens.remove(token)
            }
        }
        token.attach(job)
    }

    private fun applyLoginResult(body: JsonObject) {
        prefs.put("accountId", body.text("accountID", ""))
        prefs.put("token", body.text("authToken", ""))

        // Trim the vehicle list to the fields the UI needs; the temp record is
        // carried forward so a start can show it before the slow fetch lands.
        val vehicles = body.arrayAt("vehicles")
        if (vehicles != null) {
            val trimmed = buildJsonArray {
                for (element in vehicles) {
                    val v = element as? JsonObject ?: continue
                    add(buildJsonObject {
                        put("uvi", v.text("uvi", ""))
                        put("nickname", v.text("nickname", ""))
                        put("modelname", v.text("modelname", ""))
                        put("modelyear", v.text("modelyear", ""))
                        put("interiorTempRecords", v.objectAt("interiorTempRecords") ?: JsonObject(emptyMap()))
                    })
                }
            }
            prefs.put("vehicles", trimmed.toString())
        }

        prefs.put(
            "login_gen",
            ((prefs.getString("login_gen") ?: "0").toLongOrNull() ?: 0).let { (it + 1).toString() },
        )
        prefs.put("cache_gen", "0")
        NissanConnectCache.clear()
    }

    // ---------------------------------------------------------------- home

    /** Vehicle cards from the stored list, statuses from the cache. */
    private fun rebuildCards() {
        val list = storedVehicles()
        val cache = NissanConnectCache.all()
        vehicles.value = list.mapNotNull { v ->
            val vin = v.text("uvi", "")
            if (vin.isEmpty()) return@mapNotNull null
            VehicleUi(
                vin = vin,
                name = vehicleName(v),
                vinText = vin,
                status = VehicleStatus.fromPayload(prefs, cache.objectAt(vin), v.objectAt("interiorTempRecords")),
                fetching = false,
            )
        }
    }

    private fun storedVehicles(): List<JsonObject> =
        parseJsonArray(prefs.getString("vehicles"))?.filterIsInstance<JsonObject>() ?: emptyList()

    private fun vehicleName(v: JsonObject): String {
        val nickname = v.text("nickname")?.takeIf { it.isNotEmpty() }?.trim()
        val model = v.text("modelname")?.takeIf { it.isNotEmpty() }?.trim()
        val decoded = VinDecoder.decode(v.text("uvi", "")).let { it as? VinResult.Success }?.info
        val decodedModel = decoded?.manufacturer?.takeIf { it.isNotEmpty() }
        val year = v.text("modelyear")?.takeIf { it.isNotEmpty() && it != "null" }?.trim()
        return listOfNotNull("NISSAN", nickname ?: model ?: decodedModel, year).joinToString(" ")
    }

    /** Swipe-down refresh: live fetch for every vehicle (down-only gesture, as before). */
    fun refreshAll() {
        fetchAll(justLoggedIn = false)
    }

    /** Live battery/temp fetch for every vehicle, sequentially. */
    private fun fetchAll(justLoggedIn: Boolean) {
        val generation = ++fetchGeneration
        val list = storedVehicles()
        if (list.isEmpty()) {
            toast.value = Toast(ToastKey.NO_VEHICLES)
            return
        }
        val base = list.mapNotNull { v ->
            val vin = v.text("uvi", "")
            if (vin.isEmpty()) return@mapNotNull null
            // While the live fetch is in progress the card shows the loading
            // label: vehicle prefs carry only the temp forward, so showing a
            // partial cache would read "Temp" alone with the rest blanked.
            VehicleUi(vin = vin, name = vehicleName(v), vinText = vin, status = null, fetching = true)
        }
        vehicles.value = base
        loading.value = true
        startWatchdogs()

        val token = newToken()
        val job = scope.launch {
            var anyFailed = false
            val updated = base.toMutableList()
            for (index in updated.indices) {
                val vin = updated[index].vin
                val battery = try {
                    clientWithDeathNotice(vin).requestBatteryStatus(token)
                } catch (e: Throwable) {
                    if (token.isCancelled) break
                    anyFailed = true
                    null
                }
                if (token.isCancelled || generation != fetchGeneration) break
                if (battery == null) {
                    anyFailed = true
                } else {
                    NissanConnectCache.put(vin, battery.toJsonText())
                }
                updated[index] = updated[index].copy(
                    fetching = false,
                    status = VehicleStatus.fromPayload(
                        prefs,
                        battery,
                        list.getOrNull(index)?.objectAt("interiorTempRecords"),
                    ),
                )
                if (generation == fetchGeneration) vehicles.value = updated.toList()
            }
            if (generation == fetchGeneration) {
                prefs.put("cache_gen", loginGeneration().toString())
                hideOverlay()
                if (!anyFailed) cancelWatchdogs()
            }
            activeTokens.remove(token)
            if (justLoggedIn) fromLogin = false
        }
        token.attach(job)
    }

    /** The per-card refresh arrow: one vehicle only, no overlay across the page. */
    fun refreshVehicle(vin: String) {
        val generation = ++fetchGeneration
        val token = newToken()
        val job = scope.launch {
            vehicles.value = vehicles.value.map {
                if (it.vin == vin) it.copy(fetching = true, status = null) else it
            }
            val battery = try {
                clientWithDeathNotice(vin).requestBatteryStatus(token)
            } catch (e: Throwable) {
                if (!token.isCancelled) toast.value = Toast(ToastKey.CONNECTION_ERROR, e.message ?: "")
                null
            }
            if (generation == fetchGeneration) {
                val tempRecords = storedVehicles()
                    .firstOrNull { it.text("uvi", "") == vin }
                    ?.objectAt("interiorTempRecords")
                vehicles.value = vehicles.value.map {
                    if (it.vin != vin) it
                    else it.copy(fetching = false, status = VehicleStatus.fromPayload(prefs, battery, tempRecords))
                }
                if (battery != null) NissanConnectCache.put(vin, battery.toJsonText())
            }
            activeTokens.remove(token)
        }
        token.attach(job)
    }

    // ---------------------------------------------------------------- commands

    /** One remote command; the banner reports the outcome, a toast confirms it. */
    fun command(vin: String, key: CommandKey) {
        banner.value = null
        loading.value = true
        val token = newToken()
        val myCancel = cancelGeneration
        val job = scope.launch {
            val accountId = prefs.getString("accountId") ?: ""
            val client = clientWithDeathNotice(vin)
            try {
                when (key) {
                    CommandKey.LOCK -> {
                        if (accountId.isEmpty()) { toast.value = Toast(ToastKey.ACCOUNT_ID_MISSING); return@launch }
                        report(key, client.requestDoorLock(accountId, token), ToastKey.LOCK_SENT)
                    }
                    CommandKey.UNLOCK -> {
                        if (accountId.isEmpty()) { toast.value = Toast(ToastKey.ACCOUNT_ID_MISSING); return@launch }
                        report(key, client.requestDoorUnlock(accountId, token), ToastKey.UNLOCK_SENT)
                    }
                    CommandKey.START_TEMP ->
                        report(key, client.requestClimateControlOn(token), ToastKey.TEMP_STARTED)
                    CommandKey.STOP_TEMP ->
                        report(key, client.requestClimateControlOff(token), ToastKey.TEMP_STOPPED)
                    CommandKey.START_CHARGE ->
                        report(key, client.requestChargingStart(token), ToastKey.CHARGE_STARTED)
                    CommandKey.LOCATE -> {
                        val location = client.requestLocation(token)
                        val lat = decimalField(location, "latitudeDMS")
                        val lon = decimalField(location, "longitudeDMS")
                        when {
                            lat == null || lon == null -> report(key, false, ToastKey.LOCATION_NOT_AVAILABLE)
                            !openMaps(lat, lon) -> report(key, false, ToastKey.NO_MAPS_APP)
                            else -> banner.value = null
                        }
                    }
                }
            } catch (e: Throwable) {
                if (myCancel == cancelGeneration && !token.isCancelled) {
                    banner.value = CommandBanner(key, false)
                    toast.value = Toast(ToastKey.RAW_MESSAGE, e.message ?: "")
                }
            } finally {
                if (myCancel == cancelGeneration && !token.isCancelled) hideOverlay()
                activeTokens.remove(token)
            }
        }
        token.attach(job)
    }

    private fun report(key: CommandKey, ok: Boolean, toastKey: ToastKey) {
        banner.value = CommandBanner(key, ok)
        toast.value = Toast(if (ok) toastKey else failureKey(key))
    }

    private fun failureKey(key: CommandKey): ToastKey = when (key) {
        CommandKey.LOCK -> ToastKey.LOCK_FAILED
        CommandKey.UNLOCK -> ToastKey.UNLOCK_FAILED
        CommandKey.START_TEMP -> ToastKey.TEMP_START_FAILED
        CommandKey.STOP_TEMP -> ToastKey.TEMP_STOP_FAILED
        CommandKey.START_CHARGE -> ToastKey.CHARGE_START_FAILED
        CommandKey.LOCATE -> ToastKey.LOCATION_NOT_AVAILABLE
    }

    /** Raw coordinate string, accepted only when it is a finite decimal number. */
    private fun decimalField(location: JsonObject?, key: String): String? =
        location?.text(key)?.trim()?.takeIf { it.toDoubleOrNull()?.isFinite() == true }

    // ---------------------------------------------------------------- cancel / logout

    /** CANCEL on the overlay: stop every round and restore the cached statuses. */
    fun cancelAll() {
        cancelGeneration++
        activeTokens.forEach { it.cancel() }
        activeTokens.clear()
        cancelWatchdogs()
        hideOverlay()
        rebuildCards()
        toast.value = Toast(ToastKey.CANCELED)
    }

    /**
     * The single guarded logout path. Every trigger — manual Log out, an
     * unhealable 401, session death — funnels here, so the login screen is
     * never entered twice. A manual logout disarms auto-login and marks
     * `justLoggedOut`, so the next login screen does not auto-retry.
     */
    fun logout(manual: Boolean) {
        if (logoutStarted) return
        logoutStarted = true
        if (manual) {
            cancelAll()
            prefs.put("auto_login", "0")
            justLoggedOut = true
        }
        session.clearSession()
        CredentialStore.clear()
        NissanConnectCache.clear()
        for (key in listOf("token", "accountId", "vehicles", "login_gen", "cache_gen", "email", "password", "keep_credentials")) {
            prefs.remove(key)
        }
        banner.value = null
        vehicles.value = emptyList()
        logoutStarted = false
        screen.value = Screen.LOGIN
    }

    private fun clearDeadSession() {
        session.clearSession()
        CredentialStore.clear()
        NissanConnectCache.clear()
        for (key in listOf("token", "accountId", "vehicles", "login_gen", "cache_gen")) prefs.remove(key)
        vehicles.value = emptyList()
    }

    // ---------------------------------------------------------------- plumbing

    private fun clientWithDeathNotice(vin: String): NissanConnectClient {
        val client = NissanConnectClient(session, vin)
        client.onSessionDead = { logout(manual = false) }
        return client
    }

    /**
     * A round's cancellation token. The Job is attached right after
     * `scope.launch` returns: `launch(job)` is deprecated because it breaks
     * structured concurrency, so the token wraps the Job the builder creates.
     */
    private fun newToken(): CancellationToken {
        val token = CancellationToken()
        activeTokens.add(token)
        return token
    }

    private fun hideOverlay() {
        loading.value = false
        cancelWatchdogs()
    }

    /**
     * Watchdogs for a live refresh round: the battery endpoint is slow
     * server-side, so at 180 s the UI says the connection is slow and at 480 s
     * that the status is still loading — the round is never killed for being
     * slow, it just stops being silent about it.
     */
    private fun startWatchdogs() {
        slowWatchdog = scope.launch {
            delay(180_000)
            if (loading.value) toast.value = Toast(ToastKey.CONNECTION_SLOW)
        }
        hardWatchdog = scope.launch {
            delay(480_000)
            if (loading.value) toast.value = Toast(ToastKey.STILL_LOADING)
        }
    }

    private fun cancelWatchdogs() {
        slowWatchdog?.cancel()
        hardWatchdog?.cancel()
        slowWatchdog = null
        hardWatchdog = null
    }
}
