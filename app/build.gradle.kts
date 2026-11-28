import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.project.puk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.project.puk"
        minSdk = 28
        targetSdk = 35
        versionCode = 18
        versionName = "2.0.0-beta3"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ---- Signing ----
    // Release key NEVER lives in the repo: ~/.android/spotipuk-release.keystore
    // + spotipuk-release.properties (0600, gitignored patterns cover the rest).
    // If the properties file is missing, release falls back to the debug key so
    // a fresh clone can still build — but NEVER ship such a build publicly.
    val releaseProps = Properties().apply {
        val f = File(System.getProperty("user.home"), ".android/spotipuk-release.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    signingConfigs {
        // Debug key (fingerprint 56:46:C7… — lives OUTSIDE the repo, in
        // user.home/.android/debug.keystore; matches the first assetlinks entry).
        create("debugKey") {
            storeFile = file(
                System.getenv("SPOTIPUK_DEBUG_KEYSTORE")
                    ?: (System.getProperty("user.home") + "/.android/debug.keystore"),
            )
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("releaseKey") {
            storeFile = releaseProps.getProperty("storeFile")?.let { file(it) }
            storePassword = releaseProps.getProperty("storePassword")
            keyAlias = releaseProps.getProperty("keyAlias")
            keyPassword = releaseProps.getProperty("keyPassword")
        }
    }

    defaultConfig {
        // Jam backend; overridable in Settings. Dev: local server on LAN.
        // JAM_INVITE_ORIGIN — публичное HTTPS-приглашение (App Links);
        // JAM_SERVER_URL идёт через Caddy TLS (docs/JAM_DEPLOYMENT.md),
        // dev-IP 92.5.76.62:8080 остаётся в network_security_config для локальных проверок.
        buildConfigField("String", "JAM_SERVER_URL", "\"https://jam.spotipuk.stream\"")
        buildConfigField("String", "JAM_INVITE_ORIGIN", "\"https://jam.spotipuk.stream\"")
        buildConfigField("boolean", "JAM_DEV_ALLOW_HTTP", "true")
    }

    buildTypes {
        // Deterministic debug signing (AGP default would vary with HOME/XDG).
        debug {
            signingConfig = signingConfigs.getByName("debugKey")
        }
        release {
            signingConfig = if (releaseProps.getProperty("storeFile") != null) {
                signingConfigs.getByName("releaseKey")
            } else {
                // Local fallback; a debug-signed release must not be distributed.
                signingConfigs.getByName("debugKey")
            }
            // R8 + resource shrink: 82 MB debug → small release (Quackify-style).
            // Debug builds stay unminified (fast iterate, x86 emulator ABI intact).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                // Phone-only release; debug keeps all ABIs for the emulator.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Native playback (ExoPlayer + media session)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.dash)

    // Compose UI
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    debugImplementation(libs.compose.ui.tooling)
}
