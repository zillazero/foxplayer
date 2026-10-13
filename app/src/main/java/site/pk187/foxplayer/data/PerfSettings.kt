package site.pk187.foxplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

/** Erweiterte Wiedergabe-/Performance-Optionen (pro Nutzer). */
data class PerfSettings(
    /** auto = Hardware bevorzugt, Fallback Software/FFmpeg; sw = Software-Decoder bevorzugt; ffmpeg = FFmpeg-Audio bevorzugt */
    val decoder: String = "auto",
    /** klein / mittel / gross */
    val buffer: String = "klein",
    val preloadNext: Boolean = true,
    val tunneling: Boolean = false,
    /** aus / nahtlos / immer (Bildrate + Aufloesung) */
    val frameRateMatch: String = "nahtlos",
    val passthrough: Boolean = true,
    val animations: Boolean = true,
    val stats: Boolean = false,
) {
    /** Optionen, die einen neuen Player erfordern. */
    fun playerKey() = listOf(decoder, buffer, tunneling, passthrough, preloadNext, frameRateMatch).joinToString("|")
}

private val Context.perfDs by preferencesDataStore("fox_perf")

class PerfStore(private val ctx: Context) {
    private object K {
        val dec = stringPreferencesKey("decoder"); val buf = stringPreferencesKey("buffer")
        val pre = booleanPreferencesKey("preload"); val tun = booleanPreferencesKey("tunneling")
        val frm = stringPreferencesKey("frm"); val pt = booleanPreferencesKey("passthrough")
        val ani = booleanPreferencesKey("animations"); val st = booleanPreferencesKey("stats")
    }
    val flow = ctx.perfDs.data.map {
        PerfSettings(
            it[K.dec] ?: "auto", it[K.buf] ?: "klein", it[K.pre] ?: true, it[K.tun] ?: false,
            it[K.frm] ?: "nahtlos", it[K.pt] ?: true, it[K.ani] ?: true, it[K.st] ?: false,
        )
    }
    suspend fun save(s: PerfSettings) = ctx.perfDs.edit {
        it[K.dec] = s.decoder; it[K.buf] = s.buffer; it[K.pre] = s.preloadNext; it[K.tun] = s.tunneling
        it[K.frm] = s.frameRateMatch; it[K.pt] = s.passthrough; it[K.ani] = s.animations; it[K.st] = s.stats
    }
}
