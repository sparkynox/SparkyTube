plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.sparkynox.sparkytube"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.sparkynox.sparkytube"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // ARMv7 (armeabi-v7a) + ARM64 (arm64-v8a) — covers basically every real device.
        // x86/x86_64 skipped on purpose (emulator-only, not needed per Sparky's spec).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true // also produce one fat APK for easy sideload
        }
    }

    val hasReleaseSigning = System.getenv("KEYSTORE_PASSWORD") != null

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file("release.keystore")
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // If no KEYSTORE_PASSWORD secret is configured (e.g. first CI run,
            // or building locally from Termux without signing set up), this
            // falls back to the auto-generated debug key so the build still
            // succeeds — just re-sign properly before publishing anywhere.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.11.0")

    // ExoPlayer (Media3) — core playback + session for notification/background controls
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    implementation("org.json:json:20240303")

    // Stream extraction — actively maintained against YouTube's cipher/n-param
    // changes, published via JitPack (see settings.gradle.kts repositories).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.6.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
