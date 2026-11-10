package leafconnect

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Android side of [SettingsStore]: DataStore wrapped by Tink's AES-GCM
 * `AeadSerializer`, with the key in the Android Keystore under a fixed alias
 * (hardware-backed on most devices) and NEVER written to the store file.
 * The store name is the associated data, so ciphertext cannot be swapped
 * between stores.
 *
 * Values encrypted this way — tokens, cookies, the account email and, when
 * "Keep credentials" is on, the account password — are not readable from the
 * on-device file, and are excluded from device-to-device transfer by the
 * manifest (`allowBackup="false"`).
 *
 * A file that cannot be decrypted (torn/foreign key after a restore or factory
 * reset) surfaces as a CorruptionException; the corruption handler replaces it
 * with an empty store, so the app starts logged out instead of crashing.
 */
private class AndroidSettingsStore(private val store: DataStore<Preferences>) : SettingsStore {

    private fun snapshot(): Preferences = runBlocking { store.data.first() }

    override fun getString(key: String): String? = snapshot()[stringPreferencesKey(key)]

    override fun contains(key: String): Boolean = snapshot().contains(stringPreferencesKey(key))

    override fun put(key: String, value: String) {
        runBlocking { store.edit { prefs -> prefs[stringPreferencesKey(key)] = value } }
    }

    override fun remove(key: String) {
        runBlocking { store.edit { prefs -> prefs -= stringPreferencesKey(key) } }
    }

    companion object {
        private const val NAME = "leafconnect_secure_prefs"
        private const val KEY_ALIAS = "leafconnect_datastore_aes256_gcm"

        fun create(context: Context): AndroidSettingsStore {
            val store = DataStoreFactory.create(
                serializer = AeadSerializer(
                    aead = keystoreAead(),
                    wrappedSerializer = PreferencesFileSerializer,
                    associatedData = NAME.toByteArray(),
                ),
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { context.preferencesDataStoreFile(NAME) }
            return AndroidSettingsStore(store)
        }

        private fun keystoreAead(): Aead {
            // generateNewAes256GcmKey overwrites, so only generate when absent;
            // the key itself never leaves the Keystore.
            if (!AndroidKeystore.hasKey(KEY_ALIAS)) {
                AndroidKeystore.generateNewAes256GcmKey(KEY_ALIAS)
            }
            return AndroidKeystore.getAead(KEY_ALIAS)
        }
    }
}

/**
 * The application context, installed by `MainActivity` before anything reads
 * the store. The shared code cannot see Android's Context, so this tiny holder
 * is the seam; accessing the store before it is installed is a programming
 * error, not a runtime fallback.
 */
object AndroidAppContext {

    private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext
    }

    fun require(): Context =
        context ?: error("AndroidAppContext.install(context) must run before the settings store is used")
}

actual fun createSettingsStore(): SettingsStore = AndroidSettingsStore.create(AndroidAppContext.require())
