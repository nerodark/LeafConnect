package leafconnect

import org.json.JSONObject

class NissanConnectClient(private val session: NissanConnectSession, private val vin: String) {

    // Invoked (on the calling thread) when a 401 could NOT be self-healed —
    // i.e. re-authentication was attempted and failed ("expired" = credentials
    // rejected, "not_kept" = nothing stored to mint a token). The caller
    // typically logs out to the login screen.
    var onSessionDead: (() -> Unit)? = null

    // Central request path for ALL endpoints: sends the request, and on 401
    // re-authenticates once and retries. If re-auth fails, the session is
    // dead → onSessionDead is invoked and the 401 response is returned.
    // suppressUnauthorized is owned here, per request.
    fun request(endpoint: String, method: String = "POST", params: JSONObject? = null): NissanConnectResponse {
        session.suppressUnauthorized = true
        return try {
            var response = session.request(endpoint, method, params)
            if (response.statusCode == 401) {
                val result = try {
                    session.reauthenticate()
                } catch (_: Exception) {
                    "error"
                }
                if (result == "ok") {
                    response = session.request(endpoint, method, params)
                } else {
                    onSessionDead?.invoke()
                }
            }
            response
        } finally {
            session.suppressUnauthorized = false
        }
    }

    fun requestBatteryStatus(): JSONObject? {
        val response = request("/battery/vehicles/$vin/getChargingStatusRequest", "GET")
        return response.body
    }

    @Suppress("unused")
    fun requestVehicleData(): JSONObject? {
        val response = request("/auth/vehicles/$vin/getVehicleData", "GET")
        return response.body
    }

    fun requestChargingStart(): Boolean {
        val response = request("/battery/vehicles/$vin/remoteChargingRequest", "POST", JSONObject())
        return response.statusCode in 200..299
    }

    fun requestClimateControlOn(): Boolean {
        val response = request("/hvac/vehicles/$vin/activateHVAC", "POST", JSONObject())
        return response.statusCode in 200..299
    }

    fun requestClimateControlOff(): Boolean {
        val response = request("/hvac/vehicles/$vin/deactivateHVAC", "POST", JSONObject())
        return response.statusCode in 200..299
    }

    fun requestLocation(): JSONObject? {
        val response = request("/vehicleLocator/vehicles/$vin/refreshVehicleLocator", "POST", JSONObject())
        return response.body
    }

    fun requestDoorLock(pin: String, accountId: String): Boolean {
        val body = JSONObject().apply { put("remoteRequest", JSONObject().apply { put("authorizationKey", pin) }) }
        val response = request(
            "/remote/vehicles/$vin/accounts/$accountId/rdl/createRDL", "POST", body
        )
        return response.statusCode in 200..299
    }

    fun requestDoorUnlock(pin: String, accountId: String): Boolean {
        val body = JSONObject().apply { put("remoteRequest", JSONObject().apply { put("authorizationKey", pin) }) }
        val response = request(
            "/remote/vehicles/$vin/accounts/$accountId/rdul/createRDUL", "POST", body
        )
        return response.statusCode in 200..299
    }
}
