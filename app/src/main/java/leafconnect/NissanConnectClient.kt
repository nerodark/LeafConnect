package leafconnect

import org.json.JSONObject

/**
 * Endpoint wrapper for one vehicle.
 *
 * Central request path for ALL endpoints: send the request, and on 401
 * re-authenticate once and retry. If re-auth fails the session is dead →
 * onSessionDead is invoked and the 401 response is returned. Suppression of
 * the 401 callback is per-request, so this path owns the 401 handling.
 */
class NissanConnectClient(private val session: NissanConnectSession, private val vin: String) {

    /**
     * Invoked (on the calling worker thread) when a 401 could NOT be
     * self-healed — credentials rejected, or nothing stored to mint a token.
     */
    var onSessionDead: (() -> Unit)? = null

    private fun request(
        endpoint: String,
        method: String = "POST",
        params: JSONObject? = null,
        token: CancellationToken? = null,
    ): NissanConnectResponse {
        var response = session.request(endpoint, method, params, token, suppressUnauthorized = true)
        if (response.statusCode == 401) {
            val result = try {
                session.reauthenticate(token)
            } catch (_: Exception) {
                "error"
            }
            if (result == "ok") {
                response = session.request(endpoint, method, params, token, suppressUnauthorized = true)
            } else if (result != "canceled") {
                // A canceled round (CANCEL pressed / activity finishing) is
                // not a dead session: the caller's token guard already
                // suppresses its result reporting.
                onSessionDead?.invoke()
            }
        }
        return response
    }

    fun requestBatteryStatus(token: CancellationToken? = null): JSONObject? =
        request("/battery/vehicles/$vin/getChargingStatusRequest", "GET", null, token).body

    // Kept (not called yet): completes the endpoint set the Nissan API offers,
    // so a future vehicle-info card can use it without touching this file.
    @Suppress("unused")
    fun requestVehicleData(token: CancellationToken? = null): JSONObject? =
        request("/auth/vehicles/$vin/getVehicleData", "GET", null, token).body

    fun requestChargingStart(token: CancellationToken? = null): Boolean =
        request("/battery/vehicles/$vin/remoteChargingRequest", "POST", JSONObject(), token).statusCode in 200..299

    fun requestClimateControlOn(token: CancellationToken? = null): Boolean =
        request("/hvac/vehicles/$vin/activateHVAC", "POST", JSONObject(), token).statusCode in 200..299

    fun requestClimateControlOff(token: CancellationToken? = null): Boolean =
        request("/hvac/vehicles/$vin/deactivateHVAC", "POST", JSONObject(), token).statusCode in 200..299

    fun requestLocation(token: CancellationToken? = null): JSONObject? =
        request("/vehicleLocator/vehicles/$vin/refreshVehicleLocator", "POST", JSONObject(), token).body

    fun requestDoorLock(accountId: String, token: CancellationToken? = null): Boolean =
        doorRequest("/remote/vehicles/$vin/accounts/$accountId/rdl/createRDL", token)

    fun requestDoorUnlock(accountId: String, token: CancellationToken? = null): Boolean =
        doorRequest("/remote/vehicles/$vin/accounts/$accountId/rdul/createRDUL", token)

    private fun doorRequest(endpoint: String, token: CancellationToken?): Boolean {
        // The door endpoints take a 5-digit authorization key; the user is
        // not asked to type one — a default all-zero PIN is sent (mirroring
        // the original app, which hardcoded "00000").
        val body = JSONObject().apply {
            put("remoteRequest", JSONObject().apply { put("authorizationKey", PIN) })
        }
        return request(endpoint, "POST", body, token).statusCode in 200..299
    }

    companion object {
        private const val PIN = "00000"
    }
}
