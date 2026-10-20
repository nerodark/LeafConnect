package leafconnect

import android.content.Context
import org.json.JSONObject

/**
 * Cache of the most recent live data per VIN.
 *
 * In-memory for the life of the process, write-through to SecurePreferences
 * so it also survives process death (e.g. the system killing the app during
 * sleep and restoring the screen on wake). When a cache exists, the home
 * screen rebuilds from it and NO network refresh is triggered.
 *
 * Cleared on logout; swipe refresh always fetches fresh data and overwrites it.
 */
object NissanConnectCache {

    private const val PREF_KEY = "nissanconnect_cache"

    private val entries = HashMap<String, String>()
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = SecurePreferences.getInstance(context).getString(PREF_KEY) ?: return
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                entries[k] = root.get(k).toString()
            }
        } catch (_: Exception) {
        }
    }

    private fun persist(context: Context) {
        val root = JSONObject()
        for ((vin, json) in entries) root.put(vin, json)
        SecurePreferences.getInstance(context).put(PREF_KEY, root.toString())
    }

    fun put(context: Context, vin: String, json: String) {
        entries[vin] = json
        persist(context)
    }

    fun all(context: Context): JSONObject {
        load(context)
        val root = JSONObject()
        for ((vin, json) in entries) {
            try {
                root.put(vin, JSONObject(json))
            } catch (_: Exception) {
            }
        }
        return root
    }

    fun clear(context: Context) {
        entries.clear()
        SecurePreferences.getInstance(context).remove(PREF_KEY)
    }
}
