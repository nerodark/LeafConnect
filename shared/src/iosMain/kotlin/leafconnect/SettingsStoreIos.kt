package leafconnect

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding

/**
 * iOS side of [SettingsStore]: one JSON file in the app's Library directory.
 * Files an app creates are protected by iOS Data Protection at rest (complete
 * protection: unreadable while the device is locked) — the platform equivalent
 * of Android's Keystore-backed Tink store.
 *
 * Foundation interop details (`create`, `dataUsingEncoding`, `createFile`) are
 * exercised by the macOS build; the logic itself is plain Kotlin.
 */
private class FileSettingsStore : SettingsStore {

    private val values = mutableMapOf<String, String>()
    private var loaded = false

    private fun path(): String {
        val home = NSFileManager.defaultManager.homeDirectoryForCurrentUser?.path ?: NSTemporaryDirectory()
        return "$home/Library/leafconnect_secure_prefs.json"
    }

    private fun load() {
        if (loaded) return
        val text = NSString.create(contentsOfFile = path(), encoding = NSUTF8StringEncoding, error = null)?.toString()
        if (text != null) {
            val root = parseJsonObject(text)
            if (root != null) {
                for (key in root.keysList()) {
                    values[key] = root.text(key) ?: continue
                }
            }
        }
        loaded = true
    }

    private fun persist() {
        val root = buildJsonObject {
            for ((key, value) in values) put(key, value)
        }
        write(NSString.create(string = root.toString()).dataUsingEncoding(NSUTF8StringEncoding))
    }

    private fun write(data: NSData?) {
        if (data == null) return
        // Rewrites the file wholesale; app-created files carry iOS's default
        // complete Data Protection, so the store is encrypted at rest.
        NSFileManager.defaultManager.createFile(atPath = path(), contents = data, attributes = null)
    }

    override fun getString(key: String): String? {
        load()
        return values[key]
    }

    override fun contains(key: String): Boolean {
        load()
        return values.containsKey(key)
    }

    override fun put(key: String, value: String) {
        load()
        values[key] = value
        persist()
    }

    override fun remove(key: String) {
        load()
        values.remove(key)
        persist()
    }
}

actual fun createSettingsStore(): SettingsStore = FileSettingsStore()
