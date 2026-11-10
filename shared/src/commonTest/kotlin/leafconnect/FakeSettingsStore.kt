package leafconnect

import kotlin.test.Test
import kotlin.test.assertEquals

/** In-memory [SettingsStore] for tests; the real ones are platform-backed. */
class FakeSettingsStore : SettingsStore {

    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
