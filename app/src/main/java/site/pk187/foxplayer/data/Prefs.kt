package site.pk187.foxplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore("fox_prefs")

/** Einstellungen pro Nutzer (Android-Benutzerprofil) - nichts davon verlaesst das Geraet. */
class Prefs(private val ctx: Context) {
    private val THEME = stringPreferencesKey("theme")
    private val PLAYLIST = longPreferencesKey("playlist")
    private val LAST_CH = longPreferencesKey("last_channel")
    val theme = ctx.ds.data.map { it[THEME] ?: "foxhole" }
    val playlist = ctx.ds.data.map { it[PLAYLIST] ?: 0L }
    val lastChannel = ctx.ds.data.map { it[LAST_CH] ?: 0L }
    suspend fun setTheme(k: String) = ctx.ds.edit { it[THEME] = k }
    suspend fun setPlaylist(id: Long) = ctx.ds.edit { it[PLAYLIST] = id }
    suspend fun setLastChannel(id: Long) = ctx.ds.edit { it[LAST_CH] = id }
}
