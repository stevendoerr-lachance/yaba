package dev.steven.s3control.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The vault blob exists but cannot be read right now (Keystore hiccup). Nothing must be saved over it. */
class VaultUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Bike keys and the API session, encrypted with an AES-GCM key that never leaves
 * the Android Keystore. The VanMoof password itself is never stored.
 *
 * A blob that cannot be decrypted is never silently replaced: transient Keystore errors throw
 * [VaultUnavailableException]; a blob that is permanently unreadable (wrong tag, key gone) is
 * copied to "blob.unreadable" before the vault starts over.
 */
class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    @Volatile private var cached: Contents? = null

    data class Contents(
        val bikes: List<BikeCredentials> = emptyList(),
        val session: ApiSession? = null,
        val lastBikeMac: String? = null,
    )

    @Synchronized
    fun load(): Contents {
        cached?.let { return it }
        val blob = prefs.getString(KEY_BLOB, null) ?: return Contents().also { cached = it }
        var lastError: Exception? = null
        repeat(2) {
            try {
                val key = existingKey() ?: return quarantine(blob)
                val raw = Base64.decode(blob, Base64.NO_WRAP)
                val iv = raw.copyOfRange(0, 12)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                val json = JSONObject(String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8))
                return Contents(
                    bikes = BikeCredentials.listFromJson(json.optJSONArray("bikes")),
                    session = ApiSession.fromJson(json.optJSONObject("session")),
                    lastBikeMac = json.optStringOrNull("lastBikeMac"),
                ).also { cached = it }
            } catch (e: AEADBadTagException) {
                return quarantine(blob)
            } catch (e: IllegalArgumentException) {
                return quarantine(blob) // not Base64 / truncated
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw VaultUnavailableException(I18n.get(R.string.core_vault_unreadable), lastError)
    }

    @Synchronized
    fun save(contents: Contents) {
        val json = JSONObject().apply {
            put("bikes", BikeCredentials.listToJson(contents.bikes))
            put("session", contents.session?.toJson() ?: JSONObject.NULL)
            put("lastBikeMac", contents.lastBikeMac ?: JSONObject.NULL)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        val sealed = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        if (!prefs.edit().putString(KEY_BLOB, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) {
            throw VaultUnavailableException(I18n.get(R.string.core_vault_save_failed))
        }
        cached = contents
    }

    /** Applies [transform] to the last good contents; throws instead of writing over an unreadable vault. */
    fun update(transform: (Contents) -> Contents): Contents = synchronized(this) {
        transform(load()).also { save(it) }
    }

    private fun quarantine(blob: String): Contents {
        prefs.edit().putString(KEY_UNREADABLE, blob).remove(KEY_BLOB).commit()
        return Contents().also { cached = it }
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    private fun createKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "s3control.vault"
        const val KEY_BLOB = "blob"
        const val KEY_UNREADABLE = "blob.unreadable"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
