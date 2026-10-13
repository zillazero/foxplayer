package site.pk187.sso

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

@Serializable
data class SyncSettings(
    val theme: String? = null, val sounds: Boolean? = null, val animations: Boolean? = null,
    val accent: String? = null, val player: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class FavItem(@SerialName("item_id") val itemId: String, @SerialName("server_id") val serverId: String = FoxSso.JELLYFIN_SERVER_ID, @SerialName("updated_at") val updatedAt: String? = null)

@Serializable
data class Favorites(@SerialName("server_id") val serverId: String? = null, val items: List<FavItem> = emptyList(), @SerialName("updated_at") val updatedAt: String? = null)

@Serializable
data class ProgressItem(
    @SerialName("item_id") val itemId: String,
    @SerialName("server_id") val serverId: String = FoxSso.JELLYFIN_SERVER_ID,
    @SerialName("position_ticks") val positionTicks: Long,
    @SerialName("runtime_ticks") val runtimeTicks: Long,
    val played: Boolean = false,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("base_updated_at") val baseUpdatedAt: String? = null,
)

@Serializable
data class ProgressList(@SerialName("server_id") val serverId: String? = null, val items: List<ProgressItem> = emptyList(), @SerialName("server_time") val serverTime: String? = null)

@Serializable
data class ProgressResult(val applied: List<JsonElement> = emptyList(), @SerialName("kept_server") val keptServer: List<ProgressItem> = emptyList())

@Serializable
data class LinkState(val service: String, val connected: Boolean = false, @SerialName("account_name") val accountName: String? = null, @SerialName("updated_at") val updatedAt: String? = null)

@Serializable
data class Links(val links: List<LinkState> = emptyList())

/** 409 pk187_conflict: [server] ist die aktuelle Server-Kopie (bei progress eine Liste). */
class SyncConflict(val server: JsonElement?) : Exception("Server-Stand ist neuer (409).")

/**
 * Sync-Client für /sync/settings, /favorites, /progress, /links.
 * Erzwingt die Server-Grenzen schon lokal: nur watch.pk187.site (feste server_id), 32-Hex-IDs,
 * ≤200 Einträge pro Request (wird automatisch gestückelt), Player-Settings ≤8 KB / Tiefe ≤4, keine Secrets.
 */
class FoxSyncClient(private val auth: FoxAuthClient) {
    private val json get() = auth.json

    companion object {
        const val MAX_BATCH = 200
        const val MAX_FAVORITES = 2000
        const val MAX_PROGRESS = 5000
        const val MAX_PLAYER_BYTES = 8 * 1024
        const val MAX_PLAYER_DEPTH = 4
        private val SECRET = Regex("token|secret|password|api_key|cookie|session", RegexOption.IGNORE_CASE)
        private val HEX32 = Regex("^[0-9a-f]{32}$")

        /** Jellyfin-ID normalisieren (Bindestriche raus, klein). Wirft bei ungültiger ID. */
        fun normalizeId(id: String): String = id.replace("-", "").lowercase().also {
            require(HEX32.matches(it)) { "Ungültige Jellyfin-ID: $id" }
        }

        fun requireShopServer(serverId: String) =
            require(serverId.replace("-", "").lowercase() == FoxSso.JELLYFIN_SERVER_ID) {
                "Nur Inhalte von watch.pk187.site dürfen synchronisiert werden."
            }

        /** UTC, sekundengenau, mit Z. */
        fun nowIso(): String = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
    }

    // ---- settings ----
    suspend fun getSettings(): SyncSettings = json.decodeFromString(auth.request("GET", "/sync/settings"))

    /** Nur gesetzte Felder werden gesendet. Bei 409 → [SyncConflict] mit Server-Stand (z. B. übernehmen oder zusammenführen). */
    suspend fun putSettings(
        theme: String? = null, sounds: Boolean? = null, animations: Boolean? = null,
        accent: String? = null, player: JsonObject? = null, baseUpdatedAt: String? = null,
    ): SyncSettings {
        player?.let { validatePlayer(it) }
        val body = buildJsonObject {
            theme?.let { put("theme", it) }; sounds?.let { put("sounds", it) }
            animations?.let { put("animations", it) }; accent?.let { put("accent", it) }
            player?.let { put("player", it) }; baseUpdatedAt?.let { put("base_updated_at", it) }
        }
        return conflictAware { json.decodeFromString(auth.request("PUT", "/sync/settings", body)) }
    }

    /** Lokale Änderung schreiben; bei Konflikt Server-Stand holen, [merge] anwenden und einmal erneut senden. */
    suspend fun putSettingsMerging(local: SyncSettings, merge: (server: SyncSettings, local: SyncSettings) -> SyncSettings): SyncSettings =
        try {
            putSettings(local.theme, local.sounds, local.animations, local.accent, local.player, local.updatedAt)
        } catch (c: SyncConflict) {
            val server = c.server?.let { json.decodeFromJsonElement<SyncSettings>(it) } ?: getSettings()
            val m = merge(server, local)
            putSettings(m.theme, m.sounds, m.animations, m.accent, m.player, server.updatedAt)
        }

    // ---- favorites ----
    suspend fun getFavorites(): Favorites = json.decodeFromString(auth.request("GET", "/sync/favorites"))

    suspend fun addFavorites(ids: List<String>, baseUpdatedAt: String? = null): Favorites = favChange("PUT", ids, baseUpdatedAt)
    suspend fun removeFavorites(ids: List<String>, baseUpdatedAt: String? = null): Favorites = favChange("DELETE", ids, baseUpdatedAt)

    private suspend fun favChange(method: String, ids: List<String>, base: String?): Favorites {
        val norm = ids.map(::normalizeId).distinct()
        require(norm.size <= MAX_FAVORITES) { "Max. $MAX_FAVORITES Favoriten." }
        var last: Favorites? = null
        var b = base
        for (chunk in norm.chunked(MAX_BATCH)) {
            val body = buildJsonObject {
                put("items", buildJsonArray { chunk.forEach { add(buildJsonObject { put("item_id", it); put("server_id", FoxSso.JELLYFIN_SERVER_ID) }) } })
                b?.let { put("base_updated_at", it) }
            }
            last = conflictAware { json.decodeFromString<Favorites>(auth.request(method, "/sync/favorites", body)) }
            b = last.updatedAt // Folge-Chunks auf neuen Stand beziehen
        }
        return last ?: getFavorites()
    }

    // ---- progress ----
    suspend fun getProgress(since: String? = null): ProgressList =
        json.decodeFromString(auth.request("GET", "/sync/progress" + (since?.let { "?since=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")))

    /**
     * Batch-Upsert (gestückelt à 200). LWW serverseitig: neuere Server-Einträge kommen in kept_server zurück.
     * Mit base_updated_at: 409 lehnt den ganzen Chunk ab → [SyncConflict] (server = Liste der Server-Kopien).
     */
    suspend fun putProgress(items: List<ProgressItem>): ProgressResult {
        val applied = mutableListOf<JsonElement>(); val kept = mutableListOf<ProgressItem>()
        val norm = items.map {
            requireShopServer(it.serverId)
            require(it.positionTicks >= 0 && it.runtimeTicks >= 0) { "Ticks müssen ≥ 0 sein." }
            it.copy(itemId = normalizeId(it.itemId), serverId = FoxSso.JELLYFIN_SERVER_ID)
        }
        for (chunk in norm.chunked(MAX_BATCH)) {
            val body = buildJsonObject { put("items", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ProgressItem.serializer()), chunk)) }
            val r = conflictAware { json.decodeFromString<ProgressResult>(auth.request("PUT", "/sync/progress", body)) }
            applied += r.applied; kept += r.keptServer
        }
        return ProgressResult(applied, kept)
    }

    // ---- links (nur Status, NIE OAuth-Tokens) ----
    suspend fun getLinks(): Links = json.decodeFromString(auth.request("GET", "/sync/links"))

    suspend fun putLink(service: String, connected: Boolean, accountName: String? = null, baseUpdatedAt: String? = null): JsonElement {
        require(service == "trakt" || service == "simkl") { "Nur trakt oder simkl." }
        require((accountName?.length ?: 0) <= 64) { "account_name max. 64 Zeichen." }
        val body = buildJsonObject {
            put("service", service); put("connected", connected)
            if (connected && accountName != null) put("account_name", accountName)
            baseUpdatedAt?.let { put("base_updated_at", it) }
        }
        return conflictAware { json.parseToJsonElement(auth.request("PUT", "/sync/links", body)) }
    }

    // ---- intern ----
    private inline fun <T> conflictAware(block: () -> T): T = try { block() } catch (e: FoxApiException) {
        if (e.isConflict) throw SyncConflict(e.body?.get("server")) else throw e
    }

    private fun validatePlayer(o: JsonObject) {
        require(o.toString().toByteArray().size <= MAX_PLAYER_BYTES) { "Player-Einstellungen max. 8 KB." }
        fun walk(e: JsonElement, depth: Int) {
            require(depth <= MAX_PLAYER_DEPTH) { "Player-Einstellungen max. Tiefe $MAX_PLAYER_DEPTH." }
            when (e) {
                is JsonObject -> e.forEach { (k, v) ->
                    require(!SECRET.containsMatchIn(k)) { "Secrets dürfen nicht synchronisiert werden ($k)." }
                    walk(v, depth + 1)
                }
                is JsonArray -> e.forEach { walk(it, depth + 1) }
                is JsonPrimitive, JsonNull -> {}
            }
        }
        walk(o, 1)
    }
}
