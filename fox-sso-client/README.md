# fox-sso-client (site.pk187.sso)

Wiederverwendbare Android-Library (Kotlin) für FoxFin, FOX Player u. a.: SSO über den FOX Store,
Token-Speicher (Android Keystore, AES-256-GCM), Auto-Refresh mit Rotation, Sync-Client.
Vertrag: /workspace/foxstore-api/APP-API.md Abschnitt 8.

## Einbinden (Gradle)
```kotlin
// settings.gradle.kts
include(":fox-sso-client")
project(":fox-sso-client").projectDir = file("../fox-sso-client")
// app/build.gradle.kts
implementation(project(":fox-sso-client"))
// Plugins com.android.library + org.jetbrains.kotlin.plugin.serialization müssen im Root deklariert sein.
```
Pflicht: App mit **demselben Signaturschlüssel** wie der FOX Store signieren (`/workspace/fox-store-keys/`),
sonst gibt Android die signature-Permission `site.pk187.permission.SSO` nicht frei. Die Permission/`<queries>` kommen
über das Library-Manifest automatisch in die App. `android:allowBackup="false"` setzen (oder `fox_sso_tokens.xml` ausschließen).

## Nutzung
```kotlin
val auth = FoxAuthClient(context)              // client_id = packageName (site.pk187.foxfin / .foxplayer)
when (val r = auth.signInViaStore()) {          // im Hintergrund (IO) aufrufen
    is SsoResult.Success -> { /* angemeldet */ }
    SsoResult.StoreNotLoggedIn -> { /* "Im FOX Store anmelden" anbieten: market://details?id=site.pk187.foxstore bzw. Store öffnen */ }
    SsoResult.StoreUnavailable -> { /* eigenes Login: auth.signIn(user, pass) */ }
    is SsoResult.Error -> { /* r.message */ }
}
val sync = FoxSyncClient(auth)
val s = sync.getSettings()
try { sync.putSettings(theme = "gruen", baseUpdatedAt = s.updatedAt) } catch (c: SyncConflict) { /* c.server übernehmen */ }
sync.addFavorites(listOf("<32hex>"))
sync.putProgress(listOf(ProgressItem("<id>", positionTicks = 6e8.toLong(), runtimeTicks = 72e9.toLong(), updatedAt = FoxSyncClient.nowIso())))
auth.signOut()            // nur diese App
auth.signOut(all = true)  // überall
```
`FoxSessionExpired` → neu anmelden. `FoxApiException.isRateLimited` (429) → später erneut.

## Regeln (werden lokal schon erzwungen)
Nur `server_id` 37f9a5dd4a4e49fba3dc493068b2397d (watch.pk187.site), IDs 32 Hex, ≤200 Einträge/Request (automatisch gestückelt),
≤2000 Favoriten, Player-Settings ≤8 KB/Tiefe ≤4, keine Schlüssel mit token/secret/password/api_key/cookie/session,
Links nur Status (trakt/simkl), OAuth-Tokens bleiben auf dem Gerät.

## FoxFin (Flutter)
Die Library über einen MethodChannel im Android-Teil (`android/app`) einbinden, z. B. `fox_sso/signInViaStore`, `fox_sso/request`.
