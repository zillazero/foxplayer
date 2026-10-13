package site.pk187.sso

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** API-Fehler im Format {"code","message","data":{"status"}}; [body] = vollständige Antwort (z. B. 409 mit "server"). */
class FoxApiException(val status: Int, val code: String, message: String, val body: JsonObject? = null) : Exception(message) {
    val isConflict get() = status == 409 && code == "pk187_conflict"
    val isRateLimited get() = status == 429
}

/** Sitzung ist weg (Refresh abgelehnt/widerrufen) → neu per SSO oder Login anmelden. */
class FoxSessionExpired : Exception("Sitzung abgelaufen – bitte erneut anmelden.")

/**
 * Authentifizierter Client für pk187/v1 mit Rotation-sicherem Auto-Refresh.
 * clientId = eigene client_id (site.pk187.foxfin / site.pk187.foxplayer).
 */
class FoxAuthClient(
    context: Context,
    private val clientId: String = context.packageName,
    val vault: TokenVault = TokenVault(context),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val base: String = FoxSso.API_BASE,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val isLoggedIn get() = vault.load() != null

    /** 1. Wahl: SSO über den FOX Store. Bei Erfolg sind die Tokens gespeichert. */
    suspend fun signInViaStore(): SsoResult = withContext(Dispatchers.IO) {
        FoxSsoClient.requestSession(appContext).also { if (it is SsoResult.Success) vault.save(it.tokens) }
    }

    /** Fallback: direktes Login (Passwort wird nicht gespeichert). */
    suspend fun signIn(username: String, password: String) {
        val body = buildJsonObject {
            put("username", username); put("password", password)
            put("client_id", clientId); put("device_name", FoxSso.deviceName())
        }
        vault.save(json.decodeFromString(Tokens.serializer(), raw("POST", "/app/login", body, null).second))
    }

    /** Beendet nur die Sitzung dieser App; all=true meldet alle FOX-Apps/Geräte ab. */
    suspend fun signOut(all: Boolean = false) {
        try {
            request("POST", "/app/logout", buildJsonObject { put("all", all) })
        } catch (_: Exception) {
        } finally {
            vault.clear()
        }
    }

    /** Bearer-Request mit genau einem Refresh-Versuch bei 401. Liefert (Status, Body). */
    suspend fun request(method: String, path: String, body: JsonElement? = null, headers: Headers? = null): String {
        val t = vault.load() ?: throw FoxSessionExpired()
        return try {
            raw(method, path, body, t.accessToken, headers).second
        } catch (e: FoxApiException) {
            if (e.status != 401) throw e
            val fresh = refresh(t.refreshToken)
            raw(method, path, body, fresh.accessToken, headers).second
        }
    }

    private suspend fun refresh(used: String): Tokens = mutex.withLock {
        val cur = vault.load() ?: throw FoxSessionExpired()
        if (cur.refreshToken != used) return cur // schon rotiert – alten Token nie erneut senden
        if (cur.refreshToken.isEmpty()) { // nur Access-Token per SSO erhalten → erneut beim Store holen
            val r = FoxSsoClient.requestSession(appContext)
            if (r is SsoResult.Success) { vault.save(r.tokens); return r.tokens }
            vault.clear(); throw FoxSessionExpired()
        }
        try {
            json.decodeFromString(Tokens.serializer(), raw("POST", "/app/refresh", buildJsonObject { put("refresh_token", cur.refreshToken) }, null).second)
                .also { vault.save(it) }
        } catch (e: FoxApiException) {
            if (e.status == 400 || e.status == 401) {
                // Sitzung widerrufen → stiller Versuch über den Store, sonst neu anmelden
                val r = withContext(Dispatchers.IO) { FoxSsoClient.requestSession(appContext) }
                if (r is SsoResult.Success) { vault.save(r.tokens); return r.tokens }
                vault.clear(); throw FoxSessionExpired()
            }
            throw e
        }
    }

    private suspend fun raw(method: String, path: String, body: JsonElement?, bearer: String?, headers: Headers? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val rb = (body?.toString() ?: if (method == "GET") null else "{}")?.toRequestBody(jsonType)
            val req = Request.Builder().url(base + path)
                .header("Accept", "application/json")
                .apply { headers?.let { headers(it.newBuilder().set("Accept", "application/json").build()) } }
                .apply { bearer?.let { header("Authorization", "Bearer $it") } }
                .method(method, rb).build()
            val resp = try { http.newCall(req).execute() } catch (e: IOException) {
                throw FoxApiException(0, "network", "Keine Verbindung zum FOX-Server.")
            }
            resp.use {
                val text = it.body.string()
                if (it.isSuccessful) return@withContext it.code to text
                val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
                val code = obj?.get("code")?.jsonPrimitive?.content ?: "http_${it.code}"
                val msg = obj?.get("message")?.jsonPrimitive?.content ?: "HTTP ${it.code}"
                throw FoxApiException(it.code, code, msg, obj)
            }
        }
}
