package site.pk187.foxplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import site.pk187.foxplayer.data.*
import site.pk187.foxplayer.player.Catchup
import site.pk187.foxplayer.player.FoxPlayer

const val GROUP_ALL = "\u0000alle"
const val GROUP_FAV = "\u0000fav"

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val db = FoxDb.get(app)
    private val dao = db.dao()
    val prefs = Prefs(app)
    private val importer = Importer(db)
    val player = FoxPlayer(app)

    val theme = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, "foxhole")
    val playlists = dao.playlists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlistId = combine(prefs.playlist, playlists) { id, list ->
        list.firstOrNull { it.id == id }?.id ?: list.firstOrNull()?.id ?: 0L
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val group = MutableStateFlow(GROUP_ALL)
    val query = MutableStateFlow("")
    val status = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val playing = MutableStateFlow<Channel?>(null)
    val focused = MutableStateFlow<Channel?>(null)
    val fullscreen = MutableStateFlow(false)
    /** Zeit vom Umschalten bis zum ersten Bild (ms) - wird in der Info-Leiste angezeigt. */
    val lastZapMs = MutableStateFlow<Long?>(null)
    val epgVersion = MutableStateFlow(0)

    val groups = playlistId.flatMapLatest { dao.groups(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favCount = playlistId.flatMapLatest { dao.favoriteCount(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val channels: Flow<PagingData<Channel>> = combine(playlistId, group, query.debounce(250)) { a, b, c -> Triple(a, b, c) }
        .flatMapLatest { (pid, g, q) ->
            Pager(PagingConfig(pageSize = 60, prefetchDistance = 60, enablePlaceholders = true, initialLoadSize = 120)) {
                when {
                    q.isNotBlank() -> dao.searchPaged(pid, q.trim().split(Regex("\\s+")).joinToString(" ") { "${it.replace("\"", "")}*" })
                    g == GROUP_FAV -> dao.favoritesPaged(pid)
                    else -> dao.channelsPaged(pid, if (g == GROUP_ALL) null else g)
                }
            }.flow
        }.cachedIn(viewModelScope)

    private val epgCache = HashMap<String, List<Programme>>()

    init {
        viewModelScope.launch {
            dao.pruneProgrammes(System.currentTimeMillis() - 7 * 86400_000L)
            val last = prefs.lastChannel.first()
            if (last != 0L) dao.channel(last)?.let { play(it) }
        }
        player.exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() { lastZapMs.value = System.currentTimeMillis() - player.zapStartedAt }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                status.value = "Wiedergabefehler: ${error.errorCodeName}"
            }
        })
    }

    fun selectPlaylist(id: Long) = viewModelScope.launch { prefs.setPlaylist(id); group.value = GROUP_ALL; epgCache.clear() }
    fun setTheme(k: String) = viewModelScope.launch { prefs.setTheme(k) }

    fun play(ch: Channel, url: String? = null) {
        lastZapMs.value = null
        status.value = null
        player.play(ch, url)
        playing.value = ch
        viewModelScope.launch { prefs.setLastChannel(ch.id) }
    }

    fun zap(dir: Int) = viewModelScope.launch {
        val cur = playing.value ?: return@launch
        val g = group.value
        val grp = if (g == GROUP_ALL || g == GROUP_FAV) null else g
        val n = if (dir > 0) dao.next(cur.playlistId, grp, g == GROUP_FAV, cur.sort) else dao.prev(cur.playlistId, grp, g == GROUP_FAV, cur.sort)
        n?.let { play(it) }
    }

    fun toggleFavorite(ch: Channel) = viewModelScope.launch { dao.toggleFavorite(ch.id) }

    fun playProgramme(ch: Channel, p: Programme) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        if (p.start <= now && p.stop > now) { play(ch); return@launch }
        if (p.stop > now) { status.value = "„${p.title}“ läuft noch nicht."; return@launch }
        if (ch.catchupDays <= 0) { status.value = "Für diesen Kanal gibt es kein Catch-up."; return@launch }
        val pl = dao.playlist(ch.playlistId) ?: return@launch
        val u = Catchup.url(ch, pl.url, pl.username, pl.password, p.start, p.stop)
        if (u == null) status.value = "Catch-up-Adresse unbekannt." else { play(ch, u); status.value = "Catch-up: ${p.title}" }
    }

    suspend fun programmes(ch: Channel, from: Long, to: Long): List<Programme> {
        val k = "${ch.playlistId}|${ch.tvgId}|$from"
        epgCache[k]?.let { return it }
        return dao.programmes(ch.playlistId, listOf(ch.tvgId), from, to).also {
            if (epgCache.size > 600) epgCache.clear()
            epgCache[k] = it
        }
    }

    fun addPlaylist(p: Playlist) = viewModelScope.launch {
        val id = dao.insertPlaylist(p)
        prefs.setPlaylist(id)
        refresh(id)
    }

    fun deletePlaylist(id: Long) = viewModelScope.launch {
        db.runInTransaction { dao.deleteChannels(id); dao.deleteProgrammes(id) }
        dao.deletePlaylist(id)
    }

    fun refresh(id: Long) = viewModelScope.launch {
        val p = dao.playlist(id) ?: return@launch
        busy.value = true
        status.value = "„${p.name}“ wird geladen …"
        try {
            val r = importer.refresh(p)
            epgCache.clear(); epgVersion.value++
            status.value = "${r.channels} Kanäle in ${r.channelMs} ms, ${r.programmes} EPG-Einträge in ${r.epgMs} ms" +
                (r.epgError?.let { " (EPG-Fehler: $it)" } ?: "")
        } catch (e: Exception) {
            status.value = "Fehler beim Laden: ${e.message}"
        } finally { busy.value = false }
    }

    override fun onCleared() { player.release() }
}
