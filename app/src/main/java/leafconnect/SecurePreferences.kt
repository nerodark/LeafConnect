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
 * Keystore-backed preference store, encrypted with DataStore + Tink.
 *
 * The AES256-GCM key lives in the Android Keystore under a fixed alias
 * (hardware-backed on most devices) and is NEVER written to the store file.
 * The first implementation stored its key in the same file it encrypted, so
 * anyone who read the file could decrypt everything. androidx.security's
 * security-crypto reached end of life (whole API deprecated in 1.1.0); the
 * supported successor is encrypted DataStore. The file is a protobuf blob
 * wrapped by [androidx.datastore.tink.AeadSerializer] — encrypt on write,
 * decrypt on read — with the store name as associated data, so ciphertext
 * cannot be swapped between stores.
 *
 * Values encrypted this way — tokens, cookies, the account email and, when
 * "Keep credentials" is on, the account password — are not readable from the
 * on-device file, and are excluded from device-to-device transfer by the
 * manifest (allowBackup="false").
 *
 * A file that cannot be decrypted (torn/foreign key after a restore or
 * factory reset) surfaces as a CorruptionException. The corruption handler
 * replaces it with an empty store, so the app simply starts logged-out
 * instead of crashing — same recovery as the old implementation.
 */
class SecurePreferences private constructor(private val store: DataStore<Preferences>) {

    private fun snapshot(): Preferences = runBlocking { store.data.first() }

    fun getString(key: String): String? = snapshot()[stringPreferencesKey(key)]

    fun contains(key: String): Boolean = snapshot().contains(stringPreferencesKey(key))

    fun put(key: String, value: String) {
        runBlocking { store.edit { prefs -> prefs[stringPreferencesKey(key)] = value } }
    }

    fun remove(key: String) {
        runBlocking { store.edit { prefs -> prefs -= stringPreferencesKey(key) } }
    }

    companion object {
        private const val NAME = "leafconnect_secure_prefs"
        private const val KEY_ALIAS = "leafconnect_datastore_aes256_gcm"

        @Volatile
        private var instance: SecurePreferences? = null

        fun getInstance(context: Context): SecurePreferences {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        private fun create(context: Context): SecurePreferences {
            // The store file starts empty; previous versions wrote an encrypted
            // XML file that is not readable here (its one-time deletion already
            // ran on upgraded installs and fresh installs never had it). So an
            // upgrade launches logged out; "Keep credentials" and "Auto-login"
            // must be re-enabled once.
            val aead: Aead = keystoreAead()
            val store = DataStoreFactory.create(
                serializer = AeadSerializer(
                    aead = aead,
                    wrappedSerializer = PreferencesFileSerializer,
                    associatedData = NAME.toByteArray(),
                ),
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { context.preferencesDataStoreFile(NAME) }
            return SecurePreferences(store)
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
