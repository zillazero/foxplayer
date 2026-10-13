package site.pk187.foxplayer.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import site.pk187.foxplayer.data.Channel
import java.util.concurrent.TimeUnit

/**
 * Ein einziger, langlebiger ExoPlayer fuer Vorschau und Vollbild: Umschalten = neues MediaItem,
 * kein Neuaufbau von Decodern/Surface. Kleiner Start-Puffer (700 ms) fuer schnelles Zappen,
 * FFmpeg-Erweiterung als Fallback fuer Audio-Codecs ohne Hardware-Decoder (AC3/E-AC3/MP2/DTS).
 */
@OptIn(UnstableApi::class)
class FoxPlayer(ctx: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val dsFactory = OkHttpDataSource.Factory(http).setUserAgent("FOX-Player")

    val exo: ExoPlayer = ExoPlayer.Builder(ctx)
        .setRenderersFactory(
            DefaultRenderersFactory(ctx)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
        )
        .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 700, 1_500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .build().apply { playWhenReady = true; setWakeMode(C.WAKE_MODE_NETWORK) }

    var current: Channel? = null; private set
    var zapStartedAt = 0L; private set

    fun play(ch: Channel, overrideUrl: String? = null) {
        current = ch
        zapStartedAt = System.currentTimeMillis()
        val url = overrideUrl ?: ch.url
        val b = MediaItem.Builder().setUri(Uri.parse(url)).setMediaId("${ch.id}")
        val lower = url.substringBefore('?').lowercase()
        when {
            lower.endsWith(".m3u8") || lower.contains("/hls") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.endsWith(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
            lower.endsWith(".ts") -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        exo.setMediaItem(b.build())
        exo.prepare()
        exo.play()
    }

    fun release() = exo.release()
}

/** Catch-up-URL fuer eine vergangene Sendung (Xtream timeshift oder M3U catchup-source). */
object Catchup {
    fun url(ch: Channel, base: String, user: String, pass: String, start: Long, stop: Long): String? {
        val durMin = ((stop - start) / 60000).coerceAtLeast(1)
        if (ch.catchupSource.startsWith("xtream:")) {
            val sid = ch.catchupSource.substringAfter(':')
            val f = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
            return "${base.trimEnd('/')}/timeshift/$user/$pass/$durMin/${f.format(java.util.Date(start))}/$sid.ts"
        }
        if (ch.catchupSource.isBlank()) return null
        val s = start / 1000; val e = stop / 1000
        return ch.catchupSource.replace("{utc}", "$s").replace("\${start}", "$s").replace("{lutc}", "${System.currentTimeMillis() / 1000}")
            .replace("{utcend}", "$e").replace("\${end}", "$e").replace("{duration}", "${e - s}")
            .let { if (it.startsWith("?") || it.startsWith("&")) ch.url + it else it }
    }
}
