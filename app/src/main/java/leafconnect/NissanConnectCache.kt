package leafconnect

import android.content.Context
import org.json.JSONObject

object NissanConnectCache {

    private const val PREF_KEY = "nissanconnect_cache"

    private val entries = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lock = Any()

    @Volatile
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
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
            loaded = true
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
