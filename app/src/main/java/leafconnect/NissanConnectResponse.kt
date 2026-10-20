package leafconnect

import org.json.JSONObject

/**
 * API response wrapper.
 */
class NissanConnectResponse(
    val statusCode: Int,
    val body: JSONObject?
)
