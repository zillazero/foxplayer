package site.pk187.foxplayer

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import site.pk187.foxplayer.ui.FoxApp
import site.pk187.foxplayer.ui.FoxMaterial
import site.pk187.foxplayer.ui.MainViewModel
import site.pk187.foxplayer.ui.themeFor

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val t by vm.theme.collectAsState()
            FoxMaterial(themeFor(t)) { FoxApp(vm) }
        }
    }

    /** Fernbedienungstasten CH+/CH- funktionieren ueberall. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP -> { vm.zap(+1); true }
        KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.zap(-1); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onStop() { super.onStop(); vm.player.exo.pause() }
    override fun onStart() { super.onStart(); if (vm.playing.value != null) vm.player.exo.play() }
}
