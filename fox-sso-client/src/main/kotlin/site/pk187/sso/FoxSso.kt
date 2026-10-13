package site.pk187.sso

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle

/** Gemeinsame Konstanten des FOX-SSO (Vertrag zwischen FOX Store und den Ziel-Apps). */
object FoxSso {
    const val PERMISSION = "site.pk187.permission.SSO"
    const val STORE_PACKAGE = "site.pk187.foxstore"
    const val AUTHORITY = "site.pk187.foxstore.sso"
    val URI: Uri = Uri.parse("content://$AUTHORITY")
    const val API_BASE = "https://shop.pk187.site/wp-json/pk187/v1"
    const val JELLYFIN_SERVER_ID = "37f9a5dd4a4e49fba3dc493068b2397d"
    const val JELLYFIN_BASE = "https://watch.pk187.site"

    const val CLIENT_FOXSTORE = "site.pk187.foxstore"
    const val CLIENT_FOXFIN = "site.pk187.foxfin"
    const val CLIENT_FOXPLAYER = "site.pk187.foxplayer"

    // ContentProvider.call()-Vertrag
    const val METHOD_STATUS = "status"
    const val METHOD_EXCHANGE = "exchange"
    const val KEY_OK = "ok"
    const val KEY_LOGGED_IN = "logged_in"
    const val KEY_ERROR = "error"
    const val KEY_MESSAGE = "message"
    const val KEY_ACCESS = "access_token"
    const val KEY_REFRESH = "refresh_token"
    const val KEY_EXPIRES = "expires_in"
    const val KEY_REFRESH_EXPIRES = "refresh_expires_in"
    const val KEY_SESSION = "session_id"
    const val KEY_CLIENT = "client_id"
    const val KEY_DEVICE = "device_name"

    const val ERR_NOT_LOGGED_IN = "not_logged_in"
    const val ERR_FORBIDDEN = "forbidden"
    const val ERR_NETWORK = "network"
    const val ERR_SERVER = "server"

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60)
}

sealed class SsoResult {
    data class Success(val tokens: Tokens) : SsoResult()
    /** Store installiert, aber dort niemand angemeldet → Anmeldung im Store anbieten. */
    data object StoreNotLoggedIn : SsoResult()
    /** Store fehlt oder ist anders signiert → eigenes Login bzw. Store installieren. */
    data object StoreUnavailable : SsoResult()
    data class Error(val code: String, val message: String) : SsoResult()
}

/**
 * Fragt beim FOX Store eine eigene Sitzung für diese App an.
 * Der Store tauscht serverseitig (POST /app/exchange) und gibt NIE seinen eigenen Refresh-Token weiter.
 * Blockierend (Netzwerk im Store) → nicht im Main-Thread aufrufen.
 */
object FoxSsoClient {
    fun isStoreAvailable(context: Context): Boolean {
        val pm = context.packageManager
        return try {
            pm.getPackageInfo(FoxSso.STORE_PACKAGE, 0)
            pm.checkSignatures(context.packageName, FoxSso.STORE_PACKAGE) == PackageManager.SIGNATURE_MATCH
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isStoreLoggedIn(context: Context): Boolean {
        if (!isStoreAvailable(context)) return false
        return runCatching {
            context.contentResolver.call(FoxSso.URI, FoxSso.METHOD_STATUS, null, null)?.getBoolean(FoxSso.KEY_LOGGED_IN) == true
        }.getOrDefault(false)
    }

    fun requestSession(context: Context, deviceName: String = FoxSso.deviceName()): SsoResult {
        if (!isStoreAvailable(context)) return SsoResult.StoreUnavailable
        val extras = Bundle().apply {
            putString(FoxSso.KEY_CLIENT, context.packageName)
            putString(FoxSso.KEY_DEVICE, deviceName)
        }
        val b = try {
            context.contentResolver.call(FoxSso.URI, FoxSso.METHOD_EXCHANGE, context.packageName, extras)
        } catch (e: SecurityException) {
            return SsoResult.StoreUnavailable
        } catch (e: IllegalArgumentException) {
            return SsoResult.StoreUnavailable // Store-Version ohne SSO
        } ?: return SsoResult.StoreUnavailable
        if (b.getBoolean(FoxSso.KEY_OK)) {
            val access = b.getString(FoxSso.KEY_ACCESS) ?: return SsoResult.Error(FoxSso.ERR_SERVER, "Leere Antwort")
            return SsoResult.Success(
                Tokens(
                    accessToken = access,
                    refreshToken = b.getString(FoxSso.KEY_REFRESH) ?: "",
                    expiresIn = b.getLong(FoxSso.KEY_EXPIRES, 3600),
                    refreshExpiresIn = b.getLong(FoxSso.KEY_REFRESH_EXPIRES, 2592000),
                    sessionId = b.getString(FoxSso.KEY_SESSION) ?: "",
                ),
            )
        }
        val err = b.getString(FoxSso.KEY_ERROR) ?: FoxSso.ERR_SERVER
        return if (err == FoxSso.ERR_NOT_LOGGED_IN) SsoResult.StoreNotLoggedIn
        else SsoResult.Error(err, b.getString(FoxSso.KEY_MESSAGE) ?: "SSO fehlgeschlagen")
    }
}
