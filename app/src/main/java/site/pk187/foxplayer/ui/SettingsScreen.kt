package site.pk187.foxplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import site.pk187.foxplayer.BuildConfig
import site.pk187.foxplayer.data.Playlist

const val SOURCE_URL = "https://github.com/zillazero/foxplayer"

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: (() -> Unit)?) {
    val t = LocalFox.current
    if (onBack != null) BackHandler(onBack = onBack)
    val playlists by vm.playlists.collectAsState()
    val theme by vm.theme.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FOX", color = t.accent, fontWeight = FontWeight.Black, fontSize = 26.sp)
            Text("-PLAYER  Einstellungen", color = t.text, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Spacer(Modifier.weight(1f))
            if (onBack != null) Text("← Zurück", color = t.text, modifier = Modifier.foxFocus(onClick = onBack).padding(10.dp, 6.dp))
        }
        if (playlists.isEmpty()) Text(
            "Willkommen! Der FOX-Player bringt keine Sender mit. Trage unten deine eigene M3U-Liste oder deinen Xtream-Zugang ein.",
            color = t.muted,
        )
        status?.let { Text(it + if (busy) " …" else "", color = t.accent2) }

        Section("Playlists") {
            playlists.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = t.text, fontWeight = FontWeight.Bold)
                        Text(if (p.type == "xtream") "Xtream · ${p.url}" else "M3U · ${p.url.take(60)}", color = t.muted, fontSize = 12.sp)
                    }
                    Text("↻ Aktualisieren", color = t.text, modifier = Modifier.foxFocus(onClick = { vm.refresh(p.id) }).padding(10.dp, 6.dp))
                    Spacer(Modifier.width(6.dp))
                    var confirm by remember { mutableStateOf(false) }
                    Text(if (confirm) "Wirklich löschen?" else "Löschen", color = t.accent,
                        modifier = Modifier.foxFocus(onClick = { if (confirm) vm.deletePlaylist(p.id) else confirm = true }).padding(10.dp, 6.dp))
                }
            }
            AddPlaylist(vm)
        }

        Section("Design") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FOX_THEMES.forEach { th ->
                    Row(
                        Modifier.foxFocus(th.key == theme, onClick = { vm.setTheme(th.key) }).padding(10.dp, 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(th.accent))
                        Spacer(Modifier.width(8.dp))
                        Text(th.label, color = t.text)
                    }
                }
            }
        }

        Section("Über") {
            Text("FOX-Player ${BuildConfig.VERSION_NAME} · Lizenz: GNU GPL v3.0", color = t.text)
            Text("Enthält Ideen und Teile aus M3UAndroid (github.com/oxyroid/M3UAndroid, GPL-3.0). " +
                "Wiedergabe mit AndroidX Media3 (Apache-2.0) und FFmpeg-Decoder (LGPL/GPL).", color = t.muted, fontSize = 13.sp)
            Text("Der FOX-Player enthält keine Sender und keine Zugänge. Du bist selbst für die Inhalte deiner Playlists verantwortlich.", color = t.muted, fontSize = 13.sp)
            Text("Quellcode: $SOURCE_URL", color = t.accent2, modifier = Modifier.foxFocus(onClick = { runCatching { uri.openUri(SOURCE_URL) } }).padding(6.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalFox.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.panel).padding(16.dp)) {
        Text(title, color = t.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun AddPlaylist(vm: MainViewModel) {
    val t = LocalFox.current
    var type by remember { mutableStateOf("m3u") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    Spacer(Modifier.height(8.dp))
    Text("Neue Playlist", color = t.text, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        Text("M3U-Link", color = t.text, modifier = Modifier.foxFocus(type == "m3u", { type = "m3u" }).padding(10.dp, 6.dp))
        Text("Xtream Codes", color = t.text, modifier = Modifier.foxFocus(type == "xtream", { type = "xtream" }).padding(10.dp, 6.dp))
    }
    val fieldMod = Modifier.fillMaxWidth().widthIn(max = 640.dp)
    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = fieldMod)
    OutlinedTextField(url, { url = it }, label = { Text(if (type == "xtream") "Server (http://host:port)" else "M3U-Adresse") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = fieldMod)
    if (type == "xtream") {
        OutlinedTextField(user, { user = it }, label = { Text("Benutzername") }, singleLine = true, modifier = fieldMod)
        OutlinedTextField(pass, { pass = it }, label = { Text("Passwort") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = fieldMod)
    }
    OutlinedTextField(epg, { epg = it }, label = { Text("EPG-Adresse (XMLTV, optional)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = fieldMod)
    val ok = url.startsWith("http") && (type == "m3u" || (user.isNotBlank() && pass.isNotBlank()))
    Text("＋ Hinzufügen und laden", color = if (ok) t.accent else t.muted, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp).foxFocus(onClick = {
            if (ok) {
                vm.addPlaylist(Playlist(name = name.ifBlank { if (type == "xtream") "Xtream" else "Playlist" }, type = type,
                    url = url.trim(), username = user.trim(), password = pass, epgUrl = epg.trim()))
                name = ""; url = ""; user = ""; pass = ""; epg = ""
            }
        }).padding(12.dp, 8.dp))
}
