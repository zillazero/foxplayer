package site.pk187.foxplayer.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import site.pk187.foxplayer.data.Channel
import site.pk187.foxplayer.data.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, SETTINGS }

private val hm = SimpleDateFormat("HH:mm", Locale.GERMANY)
fun fmt(t: Long): String = hm.format(Date(t))

@Composable
fun FoxApp(vm: MainViewModel) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val playlists by vm.playlists.collectAsState()
    val fullscreen by vm.fullscreen.collectAsState()
    val t = LocalFox.current
    val anim = vm.perf.collectAsState().value.animations
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.LocalOverscrollFactory provides (if (anim) androidx.compose.foundation.LocalOverscrollFactory.current else null),
    ) {
    Box(Modifier.fillMaxSize().background(t.bg)) {
        when {
            fullscreen -> FullscreenPlayer(vm)
            screen == Screen.SETTINGS || playlists.isEmpty() -> SettingsScreen(vm, onBack = if (playlists.isEmpty()) null else ({ screen = Screen.HOME }))
            else -> HomeScreen(vm, onSettings = { screen = Screen.SETTINGS })
        }
    }
    }
}

/** Fokus-Rahmen im Akzent-Ton - auf TV gut sichtbar, auf dem Handy dezent. */
@Composable
fun Modifier.foxFocus(
    selected: Boolean = false,
    onClick: () -> Unit,
    onFocus: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
): Modifier {
    val t = LocalFox.current
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    LaunchedEffect(f) { if (f) onFocus?.invoke() }
    return this
        .clip(shape)
        .background(if (f) t.accent.copy(alpha = .22f) else if (selected) t.accent.copy(alpha = .10f) else Color.Transparent)
        .border(if (f) 2.dp else 1.dp, if (f) t.accent else if (selected) t.accent.copy(alpha = .4f) else Color.Transparent, shape)
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
fun HomeScreen(vm: MainViewModel, onSettings: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 720.dp
        if (wide) WideHome(vm, onSettings) else PhoneHome(vm, onSettings)
    }
}

@Composable
private fun WideHome(vm: MainViewModel, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().fillMaxHeight(.40f)) {
            InfoPanel(vm, Modifier.weight(1f).fillMaxHeight(), onSettings)
            Spacer(Modifier.width(12.dp))
            Preview(vm, Modifier.fillMaxHeight().aspectRatio(16 / 9f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxSize()) {
            GroupColumn(vm, Modifier.width(200.dp).fillMaxHeight())
            Spacer(Modifier.width(8.dp))
            EpgGrid(vm, Modifier.weight(1f).fillMaxHeight(), channelWidth = 230.dp, hours = 3)
        }
    }
}

@Composable
private fun PhoneHome(vm: MainViewModel, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Preview(vm, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
        InfoPanel(vm, Modifier.fillMaxWidth().padding(8.dp), onSettings, compact = true)
        GroupChips(vm)
        EpgGrid(vm, Modifier.fillMaxSize(), channelWidth = 130.dp, hours = 2)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun Preview(vm: MainViewModel, modifier: Modifier) {
    val t = LocalFox.current
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black).foxFocus(onClick = { vm.fullscreen.value = true }, shape = RoundedCornerShape(10.dp))) {
        PlayerSurface(vm, AspectRatioFrameLayout.RESIZE_MODE_FIT)
        val playing by vm.playing.collectAsState()
        if (playing == null) Text("Kanal wählen", color = t.muted, modifier = Modifier.align(Alignment.Center))
        StatsOverlay(vm, Modifier.align(Alignment.TopStart), compact = true)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(vm: MainViewModel, resize: Int) {
    val fp by vm.playerFlow.collectAsState()
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                isFocusable = false
                resizeMode = resize
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = fp.exo; it.resizeMode = resize },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun InfoPanel(vm: MainViewModel, modifier: Modifier, onSettings: () -> Unit, compact: Boolean = false) {
    val t = LocalFox.current
    val focused by vm.focused.collectAsState()
    val playing by vm.playing.collectAsState()
    val status by vm.status.collectAsState()
    val zap by vm.lastZapMs.collectAsState()
    val busy by vm.busy.collectAsState()
    val ch = focused ?: playing
    var now by remember { mutableStateOf<Programme?>(null) }
    val ver by vm.epgVersion.collectAsState()
    LaunchedEffect(ch?.id, ver) {
        now = ch?.let { c -> val n = System.currentTimeMillis(); vm.programmes(c, n - 1, n + 1).firstOrNull() }
    }
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(t.panel).padding(if (compact) 8.dp else 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FOX", color = t.accent, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("-PLAYER", color = t.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            val anim = vm.perf.collectAsState().value.animations
            if (busy) { if (anim) CircularProgressIndicator(Modifier.size(18.dp), color = t.accent, strokeWidth = 2.dp) else Text("lädt …", color = t.muted, fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            Text("⚙ Einstellungen", color = t.text, modifier = Modifier.foxFocus(onClick = onSettings).padding(8.dp, 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        if (ch != null) {
            Text(ch.name, color = t.text, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            now?.let { p ->
                Text("${fmt(p.start)} – ${fmt(p.stop)}  ${p.title}", color = t.accent2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val prog = ((System.currentTimeMillis() - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { prog }, color = t.accent, trackColor = t.bg, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp))
                if (!compact) Text(p.description, color = t.muted, maxLines = 4, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.weight(1f, fill = !compact))
        val line = listOfNotNull(status, zap?.let { "Umschaltzeit: $it ms" }).joinToString("  ·  ")
        if (line.isNotEmpty()) Text(line, color = t.muted, fontSize = 12.sp, maxLines = 2)
    }
}

@Composable
private fun GroupColumn(vm: MainViewModel, modifier: Modifier) {
    val t = LocalFox.current
    val groups by vm.groups.collectAsState()
    val sel by vm.group.collectAsState()
    val fav by vm.favCount.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val pid by vm.playlistId.collectAsState()
    LazyColumn(modifier.clip(RoundedCornerShape(10.dp)).background(t.panel).padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (playlists.size > 1) items(playlists, key = { "p${it.id}" }) { p ->
            GroupItem("▶ ${p.name}", null, p.id == pid) { vm.selectPlaylist(p.id) }
        }
        item { GroupItem("Alle Kanäle", groups.sumOf { it.count }, sel == GROUP_ALL) { vm.group.value = GROUP_ALL } }
        item { GroupItem("★ Favoriten", fav, sel == GROUP_FAV) { vm.group.value = GROUP_FAV } }
        items(groups, key = { "g" + it.groupName }) { g ->
            GroupItem(g.groupName, g.count, sel == g.groupName) { vm.group.value = g.groupName }
        }
    }
}

@Composable
private fun GroupItem(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val t = LocalFox.current
    Row(Modifier.fillMaxWidth().foxFocus(selected, onClick).padding(10.dp, 8.dp)) {
        Text(label, color = if (selected) t.accent else t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 14.sp)
        if (count != null) Text("$count", color = t.muted, fontSize = 12.sp)
    }
}

@Composable
private fun GroupChips(vm: MainViewModel) {
    val groups by vm.groups.collectAsState()
    val sel by vm.group.collectAsState()
    val t = LocalFox.current
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Chip("Alle", sel == GROUP_ALL) { vm.group.value = GROUP_ALL } }
        item { Chip("★", sel == GROUP_FAV) { vm.group.value = GROUP_FAV } }
        items(groups, key = { it.groupName }) { g -> Chip(g.groupName, sel == g.groupName) { vm.group.value = g.groupName } }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalFox.current
    Text(label, color = if (selected) t.onAccent else t.text, fontSize = 13.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) t.accent else t.panel)
            .foxFocus(selected, onClick, shape = RoundedCornerShape(16.dp)).padding(12.dp, 6.dp))
}

/**
 * TiviMate-artiges Raster: links Kanal (Nummer, Logo, Name), rechts die Sendungen im Zeitfenster.
 * Paging laedt nur sichtbare Kanaele, Sendungen werden pro sichtbarer Zeile aus Room geholt.
 * D-Pad: hoch/runter = Kanal, links/rechts = Sendungen, am Rand wird das Zeitfenster verschoben.
 */
@Composable
private fun EpgGrid(vm: MainViewModel, modifier: Modifier, channelWidth: androidx.compose.ui.unit.Dp, hours: Int) {
    val t = LocalFox.current
    val items = vm.channels.collectAsLazyPagingItems()
    val playing by vm.playing.collectAsState()
    var windowStart by remember { mutableLongStateOf(roundHalfHour(System.currentTimeMillis())) }
    val windowEnd = windowStart + hours * 3600_000L
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); nowTick = System.currentTimeMillis() } }
    val firstFocus = remember { FocusRequester() }
    val shift: (Long) -> Unit = remember { { d -> windowStart = (windowStart + d).coerceAtLeast(roundHalfHour(System.currentTimeMillis()) - 7 * 86400_000L) } }

    Column(modifier.clip(RoundedCornerShape(10.dp)).background(t.panel)) {
        // Zeitleiste
        Row(Modifier.fillMaxWidth().height(26.dp)) {
            Box(Modifier.width(channelWidth).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Text("  ${java.text.SimpleDateFormat("EE dd.MM.", Locale.GERMANY).format(Date(windowStart))}", color = t.muted, fontSize = 12.sp)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val w = maxWidth
                for (i in 0 until hours * 2) {
                    val x = w * (i / (hours * 2f))
                    Text(fmt(windowStart + i * 1800_000L), color = t.muted, fontSize = 12.sp, modifier = Modifier.offset(x = x + 4.dp).align(Alignment.CenterStart))
                }
            }
        }
        if (items.itemCount == 0) {
            Text("Keine Kanäle in dieser Auswahl.", color = t.muted, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items.itemCount, key = items.itemKey { it.id }) { idx ->
                val ch = items[idx]
                if (ch == null) Box(Modifier.fillMaxWidth().height(52.dp))
                else EpgRow(
                    vm, ch, playing?.id == ch.id, channelWidth, windowStart, windowEnd, nowTick,
                    shift = shift,
                    modifier = if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

private fun roundHalfHour(t: Long) = t - t % 1800_000L

@Composable
private fun EpgRow(
    vm: MainViewModel, ch: Channel, isPlaying: Boolean, channelWidth: androidx.compose.ui.unit.Dp,
    from: Long, to: Long, now: Long, shift: (Long) -> Unit, modifier: Modifier,
) {
    val t = LocalFox.current
    val ver by vm.epgVersion.collectAsState()
    var progs by remember(ch.id, from, ver) { mutableStateOf<List<Programme>>(emptyList()) }
    LaunchedEffect(ch.id, from, ver) { progs = vm.programmes(ch, from, to) }
    Row(modifier.fillMaxWidth().height(52.dp).padding(vertical = 2.dp)) {
        Row(
            Modifier.width(channelWidth).fillMaxHeight()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.Menu || e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BOOKMARK)) { vm.toggleFavorite(ch); true } else false
                }
                .foxFocus(isPlaying, onClick = { if (isPlaying) vm.fullscreen.value = true else vm.play(ch) }, onFocus = { vm.focused.value = ch })
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${ch.sort + 1}", color = t.muted, fontSize = 12.sp, modifier = Modifier.width(34.dp))
            AsyncImage(model = ch.logo.ifBlank { null }, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.size(40.dp, 30.dp))
            Spacer(Modifier.width(6.dp))
            Text((if (ch.favorite) "★ " else "") + ch.name, color = if (isPlaying) t.accent else t.text, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (progs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(2.dp).foxFocus(onClick = { vm.play(ch) }, onFocus = { vm.focused.value = ch }).background(t.bg2.copy(alpha = .6f)).padding(8.dp, 0.dp), contentAlignment = Alignment.CenterStart) {
                    Text("Kein Programm", color = t.muted, fontSize = 12.sp)
                }
            } else Row(Modifier.fillMaxSize()) {
                // Gewichtete Zeitleiste: Luecken = Spacer, Sendungen = Box; kein Messen pro Zeile noetig.
                var cursor = from
                progs.forEachIndexed { i, p ->
                    val s0 = maxOf(p.start, from, cursor); val e = minOf(p.stop, to)
                    if (e <= s0) return@forEachIndexed
                    if (s0 > cursor) Spacer(Modifier.weight((s0 - cursor).toFloat()))
                    cursor = e
                    key(p.id) { ProgrammeCell(vm, ch, p, i == progs.lastIndex || progs[i + 1].start >= to, p.start < from, now, shift, Modifier.weight((e - s0).toFloat())) }
                }
                if (cursor < to) Spacer(Modifier.weight((to - cursor).toFloat()))
            }
            if (now in from until to) Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight((now - from).toFloat().coerceAtLeast(1f)))
                Box(Modifier.width(2.dp).fillMaxHeight().background(t.accent))
                Spacer(Modifier.weight((to - now).toFloat().coerceAtLeast(1f)))
            }
        }
    }
}

@Composable
private fun ProgrammeCell(vm: MainViewModel, ch: Channel, p: Programme, lastInWindow: Boolean, cutLeft: Boolean, now: Long, shift: (Long) -> Unit, modifier: Modifier) {
    val t = LocalFox.current
    val live = p.start <= now && p.stop > now
    val past = p.stop <= now
    Box(
        modifier.fillMaxHeight().padding(2.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) false
                else if (ev.key == Key.DirectionRight && lastInWindow) { shift(3600_000L); true }
                else if (ev.key == Key.DirectionLeft && cutLeft) { shift(-3600_000L); true }
                else false
            }
            .background(if (live) t.accent.copy(alpha = .14f) else t.bg2, RoundedCornerShape(6.dp))
            .foxFocus(onClick = { vm.playProgramme(ch, p) }, onFocus = { vm.focused.value = ch }, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(p.title, color = if (past) t.muted else t.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmt(p.start) + (if (past && ch.catchupDays > 0) "  ⟲" else ""), color = t.muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** Statistik-Overlay: Video/Audio-Codec, HDR, Decoder, Bitrate, Video-FPS, gedroppte Frames, UI-FPS. */
@Composable
fun StatsOverlay(vm: MainViewModel, modifier: Modifier, compact: Boolean) {
    val perf by vm.perf.collectAsState()
    if (!perf.stats) return
    val fp by vm.playerFlow.collectAsState()
    val st by fp.stats.collectAsState()
    val ui by vm.uiFps.collectAsState()
    val jank by vm.uiJankPct.collectAsState()
    val lines = buildList {
        add("${st.resolution} ${"%.3f".format(st.frameRate)} fps · ${st.hdr} · ${st.videoCodec}")
        add("Video: ${"%.1f".format(st.renderedFps)} fps · gedroppt ${st.dropped} · ${st.bitrateKbps} kbit/s")
        if (!compact) {
            add("Decoder: ${st.videoDecoder}")
            add("Audio: ${st.audioCodec} · ${st.audioDecoder}")
            add("Tunneling: ${if (st.tunneled) "an" else "aus"}")
        }
        add("UI: ${"%.0f".format(ui)} fps · Jank ${"%.1f".format(jank)} %")
    }
    Column(modifier.background(Color.Black.copy(alpha = .65f), RoundedCornerShape(6.dp)).padding(8.dp, 4.dp)) {
        lines.forEach { Text(it, color = Color(0xFF7CF2AE), fontSize = if (compact) 10.sp else 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun FullscreenPlayer(vm: MainViewModel) {
    val t = LocalFox.current
    val fr = remember { FocusRequester() }
    var osd by remember { mutableStateOf(true) }
    val playing by vm.playing.collectAsState()
    val zap by vm.lastZapMs.collectAsState()
    val status by vm.status.collectAsState()
    var now by remember { mutableStateOf<Programme?>(null) }
    LaunchedEffect(playing?.id) {
        osd = true
        now = playing?.let { c -> val n = System.currentTimeMillis(); vm.programmes(c, n - 1, n + 1).firstOrNull() }
        delay(4000); osd = false
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
    BackHandler { if (osd) osd = false else vm.fullscreen.value = false }
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> { vm.zap(-1); true }
                    Key.DirectionDown, Key.ChannelDown -> { vm.zap(+1); true }
                    Key.DirectionCenter, Key.Enter -> { osd = !osd; true }
                    Key.DirectionLeft, Key.DirectionRight -> { vm.fullscreen.value = false; true }
                    else -> false
                }
            }
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { osd = !osd },
    ) {
        PlayerSurface(vm, AspectRatioFrameLayout.RESIZE_MODE_FIT)
        StatsOverlay(vm, Modifier.align(Alignment.TopEnd).padding(16.dp), compact = false)
        if (osd && playing != null) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(t.bg.copy(alpha = .85f)).padding(24.dp, 14.dp)) {
                Text(playing!!.name, color = t.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                now?.let { Text("${fmt(it.start)} – ${fmt(it.stop)}  ${it.title}", color = t.accent2) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text("▲", color = t.text, modifier = Modifier.foxFocus(onClick = { vm.zap(-1) }).padding(12.dp, 4.dp))
                    Text("▼", color = t.text, modifier = Modifier.foxFocus(onClick = { vm.zap(+1) }).padding(12.dp, 4.dp))
                    Text(if (playing!!.favorite) "★ Favorit" else "☆ Favorit", color = t.text, modifier = Modifier.foxFocus(onClick = { vm.toggleFavorite(playing!!) }).padding(12.dp, 4.dp))
                    Text("✕ Zurück", color = t.text, modifier = Modifier.foxFocus(onClick = { vm.fullscreen.value = false }).padding(12.dp, 4.dp))
                    Spacer(Modifier.weight(1f))
                    Text(listOfNotNull(status, zap?.let { "Umschaltzeit $it ms" }).joinToString(" · "), color = t.muted, fontSize = 12.sp)
                }
            }
        }
    }
}
