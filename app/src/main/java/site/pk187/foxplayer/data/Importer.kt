package site.pk187.foxplayer.data

import android.util.JsonReader
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import site.pk187.foxplayer.parse.M3uParser
import site.pk187.foxplayer.parse.XmltvParser
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class ImportResult(val channels: Int, val programmes: Int, val channelMs: Long, val epgMs: Long, val epgError: String? = null)

class Importer(private val db: FoxDb) {
    private val dao = db.dao()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun open(url: String): InputStream {
        val resp = http.newCall(Request.Builder().url(url).header("User-Agent", "FOX-Player").build()).execute()
        if (!resp.isSuccessful) { resp.close(); error("HTTP ${resp.code} fuer ${url.substringBefore('?')}") }
        val raw = resp.body.byteStream().buffered(64 * 1024)
        raw.mark(2)
        val b1 = raw.read(); val b2 = raw.read(); raw.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
    }

    suspend fun refresh(p: Playlist): ImportResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        var count = 0
        db.withTransaction {
            val favs = dao.favoriteUrls(p.id)
            dao.deleteChannels(p.id)
            val batch = ArrayList<Channel>(1000)
            fun flush() { if (batch.isNotEmpty()) { dao.insertChannels(batch); batch.clear() } }
            if (p.type == "xtream") {
                importXtream(p) { c -> batch += c.copy(sort = count++); if (batch.size >= 1000) flush() }
            } else {
                open(p.url).use { s ->
                    M3uParser.parse(s) { e ->
                        batch += Channel(
                            playlistId = p.id, sort = count++, groupName = e.group.ifBlank { "Ohne Gruppe" },
                            name = e.name, logo = e.logo, url = e.url, tvgId = e.tvgId,
                            catchupDays = e.catchupDays, catchupSource = e.catchupSource, userAgent = e.userAgent,
                        )
                        if (batch.size >= 1000) flush()
                    }
                }
            }
            flush()
            if (favs.isNotEmpty()) favs.chunked(500).forEach { dao.restoreFavorites(p.id, it) }
        }
        val t1 = System.currentTimeMillis()
        var progs = 0
        var epgErr: String? = null
        val epg = epgUrlFor(p)
        if (epg.isNotBlank()) {
            try { progs = importEpg(p, epg) } catch (e: Exception) { epgErr = e.message; Log.w("FOX", "EPG", e) }
        }
        dao.updatePlaylist(p.copy(updatedAt = System.currentTimeMillis()))
        ImportResult(count, progs, t1 - t0, System.currentTimeMillis() - t1, epgErr)
    }

    private fun base(p: Playlist) = p.url.trimEnd('/')
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun epgUrlFor(p: Playlist): String = when {
        p.epgUrl.isNotBlank() -> p.epgUrl
        p.type == "xtream" -> "${base(p)}/xmltv.php?username=${enc(p.username)}&password=${enc(p.password)}"
        else -> ""
    }

    private fun importXtream(p: Playlist, add: (Channel) -> Unit) {
        val api = "${base(p)}/player_api.php?username=${enc(p.username)}&password=${enc(p.password)}"
        val cats = HashMap<String, String>()
        open("$api&action=get_live_categories").use { s ->
            JsonReader(s.reader()).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var id = ""; var name = ""
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "category_id" -> id = r.str(); "category_name" -> name = r.str(); else -> r.skipValue()
                    }
                    r.endObject(); cats[id] = name
                }
                r.endArray()
            }
        }
        open("$api&action=get_live_streams").use { s ->
            JsonReader(s.reader()).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var sid = ""; var name = ""; var icon = ""; var epg = ""; var cat = ""; var archive = 0; var dur = 0
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "stream_id" -> sid = r.str(); "name" -> name = r.str(); "stream_icon" -> icon = r.str()
                        "epg_channel_id" -> epg = r.str(); "category_id" -> cat = r.str()
                        "tv_archive" -> archive = r.str().toIntOrNull() ?: 0
                        "tv_archive_duration" -> dur = r.str().toIntOrNull() ?: 0
                        else -> r.skipValue()
                    }
                    r.endObject()
                    add(Channel(
                        playlistId = p.id, sort = 0, groupName = cats[cat] ?: "Ohne Gruppe", name = name, logo = icon,
                        url = "${base(p)}/live/${p.username}/${p.password}/$sid.ts", tvgId = epg.ifBlank { name },
                        catchupDays = if (archive == 1) dur else 0, catchupSource = if (archive == 1) "xtream:$sid" else "",
                    ))
                }
                r.endArray()
            }
        }
    }

    private fun JsonReader.str(): String = when (peek()) {
        android.util.JsonToken.NULL -> { nextNull(); "" }
        android.util.JsonToken.BOOLEAN -> nextBoolean().toString()
        android.util.JsonToken.BEGIN_ARRAY, android.util.JsonToken.BEGIN_OBJECT -> { skipValue(); "" }
        else -> nextString()
    }

    suspend fun importEpg(p: Playlist, url: String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val from = now - 6 * 3600_000L
        val to = now + 3 * 86400_000L
        val keys = dao.tvgIds(p.id).toHashSet()
        var n = 0
        db.withTransaction {
            dao.deleteProgrammes(p.id)
            val batch = ArrayList<Programme>(2000)
            open(url).use { s ->
                XmltvParser.parse(s, from, to, keys) { pr ->
                    batch += Programme(playlistId = p.id, channelKey = pr.channel, start = pr.start, stop = pr.stop, title = pr.title, description = pr.desc)
                    n++
                    if (batch.size >= 2000) { dao.insertProgrammes(batch); batch.clear() }
                }
            }
            if (batch.isNotEmpty()) dao.insertProgrammes(batch)
        }
        n
    }
}
