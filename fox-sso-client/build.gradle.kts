// FOX SSO-Client – Android-Library für FoxFin, FOX Player (und weitere FOX-Apps).
// Einbinden: settings.gradle.kts -> include(":fox-sso-client"); project(":fox-sso-client").projectDir = file("<pfad>/fox-sso-client")
// App: implementation(project(":fox-sso-client"))
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "site.pk187.sso"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
