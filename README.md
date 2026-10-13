# FOX-Player (site.pk187.foxplayer)

IPTV-Player fuer Android, Android TV und Fire TV in einem APK.
Kotlin, Jetpack Compose, AndroidX Media3 (ExoPlayer) + FFmpeg-Decoder, Room (FTS4) + Paging 3.

Stufe 1 (MVP): M3U- und Xtream-Playlists (mehrere), Gruppen, Favoriten, Suche (FTS),
Vorschau oben, EPG-Raster unten (XMLTV, gzip), Catch-up (Xtream timeshift, catchup-source),
schnelles Umschalten (ein langlebiger Player, 700-ms-Startpuffer), CH+/CH-, 5 FOX-Themes.

Bauen: `./gradlew :app:assembleRelease` (Signatur ueber FOX_KEYSTORE_PROPERTIES).
Lizenz: GPL-3.0, siehe LICENSE und NOTICE.
