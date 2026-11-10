package leafconnect

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per-VIN battery-status cache, persisted in the encrypted store so a start
 * shows the last known state before the slow live fetch completes.
 *
 * Values are the raw response JSON per VIN. Every call happens on the request
 * mutex or the UI coroutine, so no locking is needed.
 */
object NissanConnectCache {

    private const val PREF_KEY = "nissanconnect_cache"

    private val entries = mutableMapOf<String, String>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        val raw = prefsStore.getString(PREF_KEY)
        if (raw != null) {
            val root = parseJsonObject(raw)
            if (root != null) {
                for (key in root.keysList()) {
                    entries[key] = root.text(key) ?: continue
                }
            }
        }
        loaded = true
    }

    private fun persist() {
        val root = buildJsonObject {
            for ((vin, json) in entries) put(vin, json)
        }
        prefsStore.put(PREF_KEY, root.toString())
    }

    fun put(vin: String, json: String) {
        entries[vin] = json
        persist()
    }

    /** Cached payloads per VIN; entries that are not JSON are skipped. */
    fun all(): JsonObject = run {
        load()
        buildJsonObject {
            for ((vin, json) in entries) {
                val parsed = parseJsonObject(json) ?: continue
                put(vin, parsed)
            }
        }
    }

    fun clear() {
        entries.clear()
        loaded = true
        prefsStore.remove(PREF_KEY)
    }
}
