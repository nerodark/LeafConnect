package leafconnect

import org.json.JSONObject

class NissanConnectClient(private val session: NissanConnectSession, private val vin: String) {

    fun requestBatteryStatus(): JSONObject? {
        val response = session.request("/battery/vehicles/$vin/getChargingStatusRequest", "GET")
        return response.body
    }

    @Suppress("unused")
    fun requestVehicleData(): JSONObject? {
        val response = session.request("/auth/vehicles/$vin/getVehicleData", "GET")
        return response.body
    }

    fun requestChargingStart(): Boolean {
        val response = session.request("/battery/vehicles/$vin/remoteChargingRequest", "POST", JSONObject())
        return response.statusCode in 200..299
    }

    fun requestClimateControlOn(): Boolean {
        val response = session.request("/hvac/vehicles/$vin/activateHVAC", "POST", JSONObject())
        return response.statusCode in 200..299
    }

    fun requestClimateControlOff(): Boolean {
        val response = session.request("/hvac/vehicles/$vin/deactivateHVAC", "POST", JSONObject())
        return response.statusCode in 200..299
    }

    fun requestLocation(): JSONObject? {
        val response = session.request("/vehicleLocator/vehicles/$vin/refreshVehicleLocator", "POST", JSONObject())
        return response.body
    }

    fun requestDoorLock(pin: String, accountId: String): Boolean {
        val body = JSONObject().apply { put("remoteRequest", JSONObject().apply { put("authorizationKey", pin) }) }
        val response = session.request(
            "/remote/vehicles/$vin/accounts/$accountId/rdl/createRDL", "POST", body
        )
        return response.statusCode in 200..299
    }

    fun requestDoorUnlock(pin: String, accountId: String): Boolean {
        val body = JSONObject().apply { put("remoteRequest", JSONObject().apply { put("authorizationKey", pin) }) }
        val response = session.request(
            "/remote/vehicles/$vin/accounts/$accountId/rdul/createRDUL", "POST", body
        )
        return response.statusCode in 200..299
    }
}
