package site.pk187.sso

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Tokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long = 2592000,
    @SerialName("session_id") val sessionId: String = "",
)

/**
 * Token-Speicher: AES-256-GCM, Schlüssel im Android Keystore (nicht exportierbar).
 * Private SharedPreferences (nie world-readable). Datei "<name>" in den Backup-Regeln ausschließen
 * bzw. android:allowBackup="false" setzen. Speichert nie Passwörter.
 */
class TokenVault(context: Context, name: String = "fox_sso_tokens") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val alias = "fox_sso_$name"
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun save(t: Tokens) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(json.encodeToString(Tokens.serializer(), t).toByteArray())
        prefs.edit()
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .putLong("saved_at", System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun load(): Tokens? {
        val iv = prefs.getString("iv", null) ?: return null
        val ct = prefs.getString("ct", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            json.decodeFromString(Tokens.serializer(), String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP))))
        } catch (e: Exception) {
            clear(); null
        }
    }

    @Synchronized
    fun clear() = prefs.edit().clear().apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build(),
            )
        }.generateKey()
    }
}
