plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.tuxbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tuxbox"
        minSdk = 26
        targetSdk = 35
        // CI passes -PtuxboxVersionName=1.2.3 -PtuxboxVersionCode=N for tagged releases.
        versionCode = (project.findProperty("tuxboxVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("tuxboxVersionName") as String?) ?: "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        // Releases must keep the same key so in-app updates install over the existing app.
        // CI provides it via secrets; locally the default debug keystore is the same key.
        create("release") {
            fun env(k: String) = System.getenv(k)?.takeUnless { it.isBlank() }
            storeFile = env("TUXBOX_KEYSTORE")?.let { file(it) }
                ?: file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = env("TUXBOX_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = env("TUXBOX_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = env("TUXBOX_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    // proot is shipped as lib*.so so Android extracts it to nativeLibraryDir,
    // the only app location where exec() is allowed on Android 10+.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")

    // Embedded X server + display (Termux:X11 "lorie", GPL-3.0), built by scripts/build-lorie.sh
    implementation(files("libs/lorie.aar"))
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.viewpager:viewpager:1.0.0")
}
