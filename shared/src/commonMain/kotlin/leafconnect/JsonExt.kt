package leafconnect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * JSON access for the Janus payloads.
 *
 * The app used org.json on Android only; kotlinx-serialization is the
 * multiplatform replacement, and these helpers keep the tolerant semantics the
 * backend needs: a missing key never throws, it yields the default. The
 * backend also answers some responses with malformed/empty bodies, so parsing
 * is lenient and returns null instead of throwing.
 */
internal val lenientJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Parse a response body into an object; null when it is not JSON or not an object. */
fun parseJsonObject(text: String?): JsonObject? {
    if (text.isNullOrBlank()) return null
    return runCatching { lenientJson.parseToJsonElement(text).asObject() }.getOrNull()
}

/** Parse a stored vehicle list (a top-level JSON array); null when it is not JSON. */
fun parseJsonArray(text: String?): JsonArray? {
    if (text.isNullOrBlank()) return null
    return runCatching { lenientJson.parseToJsonElement(text).asArray() }.getOrNull()
}

fun JsonElement.asArray(): JsonArray = this as JsonArray

fun JsonElement.asObject(): JsonObject = this as JsonObject

fun JsonObject?.objectAt(key: String): JsonObject? = this?.get(key) as? JsonObject

fun JsonObject?.arrayAt(key: String): JsonArray? = this?.get(key) as? JsonArray

fun JsonArray?.objectAt(index: Int): JsonObject? = this?.getOrNull(index) as? JsonObject

/** Value as text (numbers/booleans included); null when absent or not primitive. */
fun JsonObject?.text(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNullText()

fun JsonObject?.text(key: String, default: String): String = this?.text(key) ?: default

fun JsonObject?.double(key: String, default: Double): Double =
    (this?.get(key) as? JsonPrimitive)?.doubleOrNull ?: default

fun JsonObject?.int(key: String, default: Int): Int =
    (this?.get(key) as? JsonPrimitive)?.intOrNull ?: default

fun JsonObject?.long(key: String, default: Long): Long =
    (this?.get(key) as? JsonPrimitive)?.longOrNull ?: default

fun JsonObject?.boolean(key: String, default: Boolean): Boolean =
    (this?.get(key) as? JsonPrimitive)?.booleanOrNull ?: default

/** Keys of an object, in document order (kotlinx JsonObject preserves order). */
fun JsonObject?.keysList(): List<String> = this?.keys?.toList() ?: emptyList()

/** JSON text of an element, used for caching raw per-VIN payloads. */
fun JsonElement?.toJsonText(): String = this?.toString() ?: ""

private fun JsonPrimitive.contentOrNullText(): String? = this.contentOrNull

/** Build the authenticate body the way Janus expects it. */
fun authenticateBody(email: String, password: String, language: String, country: String): JsonObject =
    buildJsonObject {
        put("authenticate", buildJsonObject {
            put("userid", email)
            put("password", password)
            put("brand-s", "N")
            put("language-s", language)
            put("country", country)
        })
    }

/** Door command body with the 5-digit authorization key. */
fun remoteRequestBody(pin: String): JsonObject =
    buildJsonObject {
        put("remoteRequest", buildJsonObject { put("authorizationKey", pin) })
    }
