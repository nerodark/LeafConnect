package leafconnect

/**
 * Key/value settings store, encrypted at rest on both platforms.
 *
 * Android: DataStore wrapped by Tink's AES-GCM `AeadSerializer`, with the key
 * in the Android Keystore (`androidMain/SettingsStoreAndroid.kt`).
 * iOS: a file written with `FileProtectionType.complete`, so iOS Data
 * Protection encrypts it at rest (`iosMain/SettingsStoreIos.kt`).
 *
 * Values kept here: auth token, cookies, account ID, vehicle list, cache, the
 * account email and — when "Keep credentials" is on — the password.
 */
interface SettingsStore {

    fun getString(key: String): String?

    fun contains(key: String): Boolean

    fun put(key: String, value: String)

    fun remove(key: String)
}

/** Platform factory: Android builds the Keystore/Tink store, iOS the protected file store. */
expect fun createSettingsStore(): SettingsStore

/** One store per process; created on first use. */
internal val prefsStore: SettingsStore by lazy { createSettingsStore() }
