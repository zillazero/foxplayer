package site.pk187.foxplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import site.pk187.sso.FoxApiException
import site.pk187.sso.FoxAuthClient
import site.pk187.sso.FoxSessionExpired
import site.pk187.sso.FoxSyncClient
import site.pk187.sso.LinkState
import site.pk187.sso.SsoResult
import site.pk187.sso.SyncConflict

/**
 * FOX-Konto (SSO ueber den FOX Store, Fallback Passwort) und Sync nach APP-API.md Abschnitt 8.
 * Synchronisiert: settings (Theme, Animationen, Player-Optionen), links (Trakt/Simkl-Status, nur lesen).
 * IPTV-Favoriten bleiben lokal (API nimmt nur Jellyfin-IDs der Shop-Instanz an).
 * favorites/progress fuer Shop-Jellyfin sind ueber [sync] verfuegbar, sobald der Player Shop-Inhalte abspielt.
 */
class AccountSync(ctx: Context) {
    val auth = FoxAuthClient(ctx)
    val sync = FoxSyncClient(auth)
    val loggedIn = MutableStateFlow(auth.isLoggedIn)
    val message = MutableStateFlow<String?>(null)
    val links = MutableStateFlow<List<LinkState>>(emptyList())
    private var settingsBase: String? = null

    suspend fun signInViaStore(): SsoResult {
        val r = auth.signInViaStore()
        message.value = when (r) {
            is SsoResult.Success -> "Über den FOX Store angemeldet."
            SsoResult.StoreNotLoggedIn -> "Im FOX Store ist niemand angemeldet. Bitte dort anmelden oder hier mit Passwort."
            SsoResult.StoreUnavailable -> "FOX Store nicht gefunden – bitte mit Benutzername und Passwort anmelden."
            is SsoResult.Error -> "Anmeldung fehlgeschlagen: ${r.message}"
        }
        loggedIn.value = auth.isLoggedIn
        return r
    }

    suspend fun signIn(user: String, pass: String) {
        try { auth.signIn(user, pass); message.value = "Angemeldet." }
        catch (e: FoxApiException) { message.value = if (e.isRateLimited) "Zu viele Versuche – bitte später erneut." else "Anmeldung fehlgeschlagen: ${e.message}" }
        catch (e: Exception) { message.value = "Anmeldung fehlgeschlagen: ${e.message}" }
        loggedIn.value = auth.isLoggedIn
    }

    suspend fun signOut(all: Boolean) {
        auth.signOut(all); loggedIn.value = false; links.value = emptyList(); settingsBase = null
        message.value = if (all) "Überall abgemeldet." else "Abgemeldet."
    }

    private inline fun <T> guarded(block: () -> T): T? = try { block() } catch (e: FoxSessionExpired) {
        loggedIn.value = false; message.value = e.message; null
    } catch (e: Exception) { message.value = "Sync: ${e.message}"; null }

    /** Server-Stand holen; liefert (theme, PerfSettings) falls vorhanden. */
    suspend fun pull(local: PerfSettings): Pair<String?, PerfSettings?>? = guarded {
        val s = sync.getSettings()
        settingsBase = s.updatedAt
        links.value = runCatching { sync.getLinks().links }.getOrDefault(emptyList())
        if (s.updatedAt == null) return@guarded null
        val perf = s.player?.let { perfFromJson(it, local) }?.let { p -> s.animations?.let { p.copy(animations = it) } ?: p }
        s.theme to perf
    }

    /** Lokale Aenderung hochladen; bei Konflikt gewinnt die lokale (neuere) Aenderung. */
    suspend fun push(theme: String, perf: PerfSettings) {
        if (!auth.isLoggedIn) return
        guarded {
            val r = try {
                sync.putSettings(theme = theme, animations = perf.animations, player = perfToJson(perf), baseUpdatedAt = settingsBase)
            } catch (c: SyncConflict) {
                val server = sync.getSettings()
                sync.putSettings(theme = theme, animations = perf.animations, player = perfToJson(perf), baseUpdatedAt = server.updatedAt)
            }
            settingsBase = r.updatedAt
        }
    }

    companion object {
        fun perfToJson(p: PerfSettings): JsonObject = buildJsonObject {
            put("decoder", p.decoder); put("buffer", p.buffer); put("preload_next", p.preloadNext)
            put("tunneling", p.tunneling); put("frame_rate_match", p.frameRateMatch)
            put("passthrough", p.passthrough); put("stats", p.stats)
        }
        fun perfFromJson(j: JsonObject, d: PerfSettings): PerfSettings {
            fun str(k: String, def: String) = j[k]?.jsonPrimitive?.content ?: def
            fun bool(k: String, def: Boolean) = j[k]?.jsonPrimitive?.booleanOrNull ?: def
            return d.copy(
                decoder = str("decoder", d.decoder).takeIf { it in setOf("auto", "sw", "ffmpeg") } ?: d.decoder,
                buffer = str("buffer", d.buffer).takeIf { it in setOf("klein", "mittel", "gross") } ?: d.buffer,
                preloadNext = bool("preload_next", d.preloadNext), tunneling = bool("tunneling", d.tunneling),
                frameRateMatch = str("frame_rate_match", d.frameRateMatch).takeIf { it in setOf("aus", "nahtlos", "immer") } ?: d.frameRateMatch,
                passthrough = bool("passthrough", d.passthrough), stats = bool("stats", d.stats),
            )
        }
    }
}
