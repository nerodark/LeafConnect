package leafconnect

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES/GCM/NoPadding 256-bit encrypted preferences store.
 * A per-install random key is provisioned on first use and stored (base64) in
 * the backing SharedPreferences file; values are stored as base64(IV || ciphertext).
 */
class SecurePreferences private constructor(private val prefs: SharedPreferences) {

    private val aesKey: SecretKeySpec

    init {
        val stored = prefs.getString(KEY_STORE, null)
        val keyBytes: ByteArray = if (stored != null) {
            Base64.decode(stored, Base64.NO_WRAP)
        } else {
            val generated = generateAesKey()
            prefs.edit { putString(KEY_STORE, Base64.encodeToString(generated, Base64.NO_WRAP)) }
            generated
        }
        aesKey = SecretKeySpec(keyBytes, "AES")
    }

    fun getString(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            decrypt(encoded)
        } catch (_: Exception) {
            null
        }
    }

    fun put(key: String, value: String) {
        prefs.edit { putString(key, encrypt(value)) }
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun remove(key: String) {
        prefs.edit { remove(key) }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(CRYPTO_ALGORITHM)
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)
        val cipher = Cipher.getInstance(CRYPTO_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun generateAesKey(): ByteArray {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256, SecureRandom())
        return generator.generateKey().encoded
    }

    companion object {
        private const val KEY_STORE = "secure_prefs_aes_key"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val CRYPTO_ALGORITHM = "AES/GCM/NoPadding"

        @Volatile
        private var instance: SecurePreferences? = null

        fun getInstance(context: Context): SecurePreferences {
            return instance ?: synchronized(this) {
                instance ?: SecurePreferences(
                    context.applicationContext
                        .getSharedPreferences("leafconnect_secure_prefs", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }
}
