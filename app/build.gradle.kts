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
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK is installable out of the box.
            // Replace with your own signing config before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

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
