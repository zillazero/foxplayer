package site.pk187.foxplayer.data

import site.pk187.foxplayer.BuildConfig

/**
 * Externe Dienste (Roadmap Stufe 2). Schluessel kommen ausschliesslich aus der lokalen Datei
 * foxplayer-keys.properties (ausserhalb des Repos) ueber BuildConfig. Ohne Schluessel bleibt die
 * jeweilige Funktion in der Oberflaeche ausgeblendet.
 */
object Integrations {
    val trakt get() = BuildConfig.TRAKT_CLIENT_ID.isNotBlank() && BuildConfig.TRAKT_CLIENT_SECRET.isNotBlank()
    val simkl get() = BuildConfig.SIMKL_CLIENT_ID.isNotBlank()
    val tmdb get() = BuildConfig.TMDB_API_KEY.isNotBlank()
}
