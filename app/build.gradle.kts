import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "site.pk187.foxplayer"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "site.pk187.foxplayer"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        // API-Schluessel nur aus lokaler Datei (nicht im Repo). Fehlen sie, blendet die App die Funktion aus.
        val keys = Properties().apply {
            val f = file(System.getenv("FOX_API_KEYS") ?: "/workspace/fox-apps/foxplayer-keys.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun key(n: String) = "\"" + (keys.getProperty(n) ?: "").replace("\"", "") + "\""
        buildConfigField("String", "TRAKT_CLIENT_ID", key("TRAKT_CLIENT_ID"))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", key("TRAKT_CLIENT_SECRET"))
        buildConfigField("String", "SIMKL_CLIENT_ID", key("SIMKL_CLIENT_ID"))
        buildConfigField("String", "TMDB_API_KEY", key("TMDB_API_KEY"))
    }

    val foxKeyProps = Properties().apply {
        val f = file(System.getenv("FOX_KEYSTORE_PROPERTIES") ?: "/workspace/fox-store-keys/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (foxKeyProps.isNotEmpty()) create("fox") {
            storeFile = file(foxKeyProps.getProperty("storeFile"))
            storePassword = foxKeyProps.getProperty("storePassword")
            keyAlias = foxKeyProps.getProperty("keyAlias")
            keyPassword = foxKeyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfigs.findByName("fox")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { applicationIdSuffix = ".debug" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    val media3 = "1.9.0"
    val room = "2.8.4"
    implementation(project(":fox-sso-client"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.metrics:metrics-performance:1.0.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    implementation("androidx.room:room-paging:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("androidx.paging:paging-compose:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.2")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}

// Nur lokal fuer Vergleichsmessung: -Pbench bindet den M3UAndroid-Referenzparser (ausserhalb des Repos) ein.
if (project.hasProperty("bench")) {
    android.sourceSets.getByName("test").kotlin.directories.add("/workspace/fox-apps/bench-ref")
    tasks.withType<Test>().configureEach {
        systemProperty("bench", "1")
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false }
    }
}
