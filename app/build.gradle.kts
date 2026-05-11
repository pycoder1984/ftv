plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.vidking.firetv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vidking.firetv"
        minSdk = 22
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TMDB_API_KEY", "\"a2fb4f4ce28576beb1dfb5de1ca071e4\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"KNJ9bsLI8YE1j24qshjY5PnuVp9Ozr18\"")
        // Default Febbox endpoint + siteadmin token baked into the APK so a
        // fresh install works out of the box. User can override either in
        // Settings → "Clear Febbox settings" reverts both back to these.
        buildConfigField("String", "FEBBOX_DEFAULT_BASE_URL", "\"https://www.rnrvibe.com/api/siteadmin/febbox\"")
        buildConfigField("String", "FEBBOX_DEFAULT_TOKEN", "\"rnrvibe-siteadmin-c4f7a2e9b6d3a1f80d2e9b4c6a8e1f7d-2026\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Pin a stable debug keystore (committed to the repo) so debug APKs built
    // here, on CI, or on a fresh machine all share the same signing cert.
    // Without this, Android treats a re-signed reinstall as a fresh install
    // and wipes SharedPreferences — which is what happened in 2026-05.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // Image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0")

    // Media3 ExoPlayer — native HLS / DASH playback. Fire TV's WebView can't
    // reliably play HLS or HEVC, so we resolve direct stream URLs and render
    // them through a hardware-accelerated SurfaceView.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-ui-leanback:1.4.1")
}
