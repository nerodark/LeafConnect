package leafconnect

import kotlinx.serialization.json.JsonObject

/** API response wrapper: status code plus the parsed body (null when it is not JSON). */
class NissanConnectResponse(
    val statusCode: Int,
    val body: JsonObject?,
)
