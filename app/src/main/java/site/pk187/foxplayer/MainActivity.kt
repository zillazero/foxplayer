package site.pk187.foxplayer

import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.metrics.performance.JankStats
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import site.pk187.foxplayer.ui.FoxApp
import site.pk187.foxplayer.ui.FoxMaterial
import site.pk187.foxplayer.ui.MainViewModel
import site.pk187.foxplayer.ui.themeFor
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var jank: JankStats? = null
    private var frames = 0; private var janky = 0; private var windowStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.data?.let { vm.handleDeepLink(it) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val t by vm.theme.collectAsState()
            FoxMaterial(themeFor(t)) { FoxApp(vm) }
        }
        // Frame-Timing der Oberflaeche (JankStats): Bilder/s und Anteil ruckelnder Frames, 1-s-Fenster.
        jank = JankStats.createAndTrack(window) { f ->
            frames++; if (f.isJank) janky++
            val now = f.frameStartNanos / 1_000_000
            if (windowStart == 0L) windowStart = now
            if (now - windowStart >= 1000) {
                vm.uiFps.value = frames * 1000f / (now - windowStart)
                vm.uiJankPct.value = if (frames > 0) janky * 100f / frames else 0f
                frames = 0; janky = 0; windowStart = now
            }
        }
        // Automatischer Bildraten-/Aufloesungswechsel ("immer"): passenden Display-Modus waehlen.
        lifecycleScope.launch {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            combine(vm.playerFlow.flatMapLatest { it.stats }, vm.perf, vm.fullscreen) { st, s, fs ->
                (if (s.frameRateMatch == "immer" && fs) st.frameRate else 0f) to st.resolution
            }.distinctUntilChanged().collect { (fps, res) -> applyDisplayMode(fps, res) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.data?.let { vm.handleDeepLink(it) }
    }

    private var appliedMode = -1
    private fun applyDisplayMode(fps: Float, res: String) {
        val display: Display = if (Build.VERSION.SDK_INT >= 30) display ?: return else @Suppress("DEPRECATION") windowManager.defaultDisplay
        var target = 0
        if (fps > 1f) {
            val h = res.substringAfter('x', "0").toIntOrNull() ?: 0
            val modes = display.supportedModes
            fun rateOk(r: Float): Boolean { val m = r / fps; return abs(m - m.roundToInt()) < 0.01f && m.roundToInt() in 1..2 }
            val cand = modes.filter { rateOk(it.refreshRate) }
            // kleinste Aufloesung >= Video, sonst die groesste; bei gleicher Aufloesung 1x vor 2x Bildrate
            val best = cand.filter { it.physicalHeight >= h }.minWithOrNull(compareBy({ it.physicalHeight }, { it.refreshRate }))
                ?: cand.maxByOrNull { it.physicalHeight }
            target = best?.modeId ?: 0
        }
        if (target == appliedMode) return
        appliedMode = target
        window.attributes = window.attributes.apply { preferredDisplayModeId = target }
    }

    /** Fernbedienungstasten CH+/CH- funktionieren ueberall. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP -> { vm.zap(+1); true }
        KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.zap(-1); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onResume() { super.onResume(); jank?.isTrackingEnabled = true }
    override fun onPause() { super.onPause(); jank?.isTrackingEnabled = false }
    override fun onStop() { super.onStop(); vm.player.exo.pause(); applyDisplayMode(0f, "") }
    override fun onStart() { super.onStart(); if (vm.playing.value != null) vm.player.exo.play() }
}
