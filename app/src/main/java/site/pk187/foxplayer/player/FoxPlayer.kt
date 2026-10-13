package site.pk187.foxplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import site.pk187.foxplayer.data.Channel
import site.pk187.foxplayer.data.PerfSettings
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Live-Statistik fuer das Overlay. */
data class PlaybackStats(
    val videoCodec: String = "-", val resolution: String = "-", val frameRate: Float = 0f, val hdr: String = "-",
    val videoDecoder: String = "-", val audioCodec: String = "-", val audioDecoder: String = "-",
    val bitrateKbps: Int = 0, val renderedFps: Float = 0f, val dropped: Int = 0, val tunneled: Boolean = false,
)

/**
 * Langlebiger ExoPlayer fuer Vorschau und Vollbild, konfiguriert aus [PerfSettings]:
 * Decoder-Wahl (Hardware/Software/FFmpeg), Puffer, Tunneling, Audio-Passthrough, Frame-Rate-Strategie,
 * Vorladen der Nachbarkanaele ueber DefaultPreloadManager (nur Quelle + Spurauswahl, keine
 * Mediendaten -> keine veralteten Live-Puffer).
 */
@OptIn(UnstableApi::class)
class FoxPlayer(ctx: Context, val settings: PerfSettings) {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val dsFactory = OkHttpDataSource.Factory(http).setUserAgent("FOX-Player")
    val stats = MutableStateFlow(PlaybackStats())

    private val renderers = object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
            val b = DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            // Passthrough aus: nur PCM melden -> AC3/E-AC3/DTS werden dekodiert (Plattform oder FFmpeg).
            if (!settings.passthrough) b.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            return b.build()
        }
    }.apply {
        setExtensionRendererMode(
            if (settings.decoder == "ffmpeg") DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        )
        setEnableDecoderFallback(true)
        if (settings.decoder == "sw") setMediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).sortedByDescending { it.softwareOnly }
        }
    }

    private val loadControl = when (settings.buffer) {
        "gross" -> DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 90_000, 3_000, 6_000)
        "mittel" -> DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
        else -> DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 700, 1_500)
    }.setPrioritizeTimeOverSizeThresholds(true).build()

    private val trackSelectorFactory = TrackSelector.Factory { c ->
        DefaultTrackSelector(c).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(settings.tunneling)
                .setPreferredAudioLanguage("de").setPreferredTextLanguage("de"))
        }
    }

    private val preloadControl = TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { rank ->
        if (abs(rank) == 1) DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
        else DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
    }

    private val pmBuilder = DefaultPreloadManager.Builder(ctx, preloadControl)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
        .setRenderersFactory(renderers)
        .setLoadControl(loadControl)
        .setTrackSelectorFactory(trackSelectorFactory)

    val exo: ExoPlayer = pmBuilder.buildExoPlayer(ExoPlayer.Builder(ctx)).apply {
        playWhenReady = true
        setWakeMode(C.WAKE_MODE_NETWORK)
        videoChangeFrameRateStrategy = if (settings.frameRateMatch == "aus") C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
    }
    private val preload: DefaultPreloadManager = pmBuilder.build()
    private val preloaded = ArrayList<MediaItem>(2)

    var zapStartedAt = 0L; private set

    private var videoDecoder = "-"; private var audioDecoder = "-"; private var bitrate = 0L
    private var lastRendered = 0; private var lastTick = 0L
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { updateStats(); main.postDelayed(this, 1000) }
    }

    init {
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(e: AnalyticsListener.EventTime, name: String, a: Long, b: Long) { videoDecoder = name }
            override fun onAudioDecoderInitialized(e: AnalyticsListener.EventTime, name: String, a: Long, b: Long) { audioDecoder = name }
            override fun onBandwidthEstimate(e: AnalyticsListener.EventTime, ms: Int, bytes: Long, est: Long) { bitrate = est }
            override fun onAudioInputFormatChanged(e: AnalyticsListener.EventTime, f: Format, r: DecoderReuseEvaluation?) {
                audioDecoder = if (f.sampleMimeType in PASSTHROUGH_MIMES && settings.passthrough) "Passthrough?" else audioDecoder
            }
        })
        main.post(ticker)
    }

    private fun updateStats() {
        val v = exo.videoFormat; val a = exo.audioFormat
        val c = exo.videoDecoderCounters
        val now = System.currentTimeMillis()
        val rendered = c?.renderedOutputBufferCount ?: 0
        val fps = if (lastTick > 0 && rendered >= lastRendered) (rendered - lastRendered) * 1000f / (now - lastTick) else 0f
        lastRendered = rendered; lastTick = now
        stats.value = PlaybackStats(
            videoCodec = v?.let { it.codecs ?: it.sampleMimeType } ?: "-",
            resolution = v?.let { "${it.width}x${it.height}" } ?: "-",
            frameRate = v?.frameRate ?: 0f,
            hdr = hdrName(v),
            videoDecoder = videoDecoder,
            audioCodec = a?.let { "${it.sampleMimeType?.substringAfter('/')} ${it.channelCount}ch" } ?: "-",
            audioDecoder = audioDecoder,
            bitrateKbps = (v?.bitrate?.takeIf { it > 0 } ?: bitrate.toInt()) / 1000,
            renderedFps = fps,
            dropped = c?.droppedBufferCount ?: 0,
            tunneled = settings.tunneling,
        )
    }

    fun itemFor(ch: Channel, url: String? = null): MediaItem {
        val u = url ?: ch.url
        val b = MediaItem.Builder().setUri(Uri.parse(u)).setMediaId(if (url == null) "ch${ch.id}" else "cu${ch.id}:$u")
        val lower = u.substringBefore('?').lowercase()
        when {
            lower.endsWith(".m3u8") || lower.contains("/hls") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.endsWith(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
            lower.endsWith(".ts") -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        return b.build()
    }

    fun play(ch: Channel, overrideUrl: String? = null) {
        zapStartedAt = System.currentTimeMillis()
        val item = itemFor(ch, overrideUrl)
        val src = if (settings.preloadNext && overrideUrl == null) preload.getMediaSource(item) else null
        if (src != null) exo.setMediaSource(src) else exo.setMediaItem(item)
        exo.prepare()
        exo.play()
    }

    /** Nachbarkanaele vorbereiten (Rang -1/+1). */
    fun preloadNeighbours(prev: Channel?, next: Channel?) {
        if (!settings.preloadNext) return
        preloaded.forEach { preload.remove(it) }
        preloaded.clear()
        prev?.let { val i = itemFor(it); preload.add(i, -1); preloaded += i }
        next?.let { val i = itemFor(it); preload.add(i, 1); preloaded += i }
        preload.setCurrentPlayingIndex(0)
        preload.invalidate()
    }

    fun release() {
        main.removeCallbacks(ticker)
        preload.release()
        exo.release()
    }

    companion object {
        val PASSTHROUGH_MIMES = setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD)
        fun hdrName(f: Format?): String {
            if (f == null) return "-"
            if (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dv") == true) return "Dolby Vision"
            return when (f.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10"
                C.COLOR_TRANSFER_HLG -> "HLG"
                else -> "SDR"
            }
        }
    }
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
