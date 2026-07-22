package ai.focal.app.llm

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-provider API-key storage backed by Android Keystore AES-256-GCM.
 *
 * The historical alias and preferences filename are stable compatibility IDs;
 * changing either would strand credentials from existing repaired builds. If
 * the keystore is unavailable, writes fail closed instead of storing recoverable
 * plaintext or Base64 obfuscation.
 */
object SecureStorage {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "sonario_api_keys"
    private const val PREFS = "sonario_secure_keys"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val IV_SEPARATOR = "]"
    private const val TAG_BITS = 128

    /** Encrypt and persist a provider API key. Empty/null clears it. */
    fun storeKey(context: Context, providerId: String, key: String?) {
        if (key.isNullOrBlank()) {
            clearKey(context, providerId)
            return
        }
        try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val ciphertext = Base64.encodeToString(
                cipher.doFinal(key.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP,
            )
            if (!prefs(context).edit()
                    .putString(providerKey(providerId), "$iv$IV_SEPARATOR$ciphertext")
                    .commit()
            ) {
                throw CredentialStorageException("Encrypted credential could not be persisted.")
            }
        } catch (error: Exception) {
            if (error is CredentialStorageException) throw error
            throw CredentialStorageException(
                "Android Keystore could not encrypt the credential.",
                error,
            )
        }
    }

    /** Decrypt a key, migrating the historical Base64 fallback only if encryption succeeds. */
    fun getKey(context: Context, providerId: String): String? {
        val raw = prefs(context).getString(providerKey(providerId), null) ?: return null
        return try {
            if (raw.startsWith(LEGACY_BASE64_PREFIX)) {
                val legacy = Base64.decode(
                    raw.removePrefix(LEGACY_BASE64_PREFIX),
                    Base64.NO_WRAP,
                ).toString(Charsets.UTF_8)
                storeKey(context, providerId, legacy)
                return legacy
            }

            val parts = raw.split(IV_SEPARATOR, limit = 2)
            if (parts.size != 2) {
                throw CredentialStorageException("Stored credential has an invalid format.")
            }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: Exception) {
            if (error is CredentialStorageException) throw error
            throw CredentialStorageException(
                "Stored credential could not be decrypted with Android Keystore.",
                error,
            )
        }
    }

    /** True only when a stored key can be decrypted successfully. */
    fun hasKey(context: Context, providerId: String): Boolean =
        getKey(context, providerId) != null

    /** True when an entry exists, including one that cannot be decrypted. */
    fun hasStoredKey(context: Context, providerId: String): Boolean =
        prefs(context).contains(providerKey(providerId))

    fun clearKey(context: Context, providerId: String) {
        if (!prefs(context).edit().remove(providerKey(providerId)).commit()) {
            throw CredentialStorageException("Stored credential could not be removed.")
        }
    }

    fun clearAll(context: Context) {
        if (!prefs(context).edit().clear().commit()) {
            throw CredentialStorageException("Stored credentials could not be removed.")
        }
    }

    /** Masked display: no output for short values; otherwise four prefix and three suffix chars. */
    fun masked(key: String?): String = when {
        key.isNullOrBlank() -> "Not set"
        key.length <= 10 -> "••••••"
        else -> "${key.take(4)}…${key.takeLast(3)}"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun providerKey(providerId: String) = "key_$providerId"

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getEntry(KEY_ALIAS, null)?.let {
            return (it as KeyStore.SecretKeyEntry).secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private const val LEGACY_BASE64_PREFIX = "b64:"
}

class CredentialStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
