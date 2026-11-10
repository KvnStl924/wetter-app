import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Version: X.Y.Z (X = großes Update, Y = kleines Update, Z = Fehlerbehebung) ----
val versionMajor = 2
val versionMinor = 0
val versionPatch = 1

// ---- Signierschlüssel: lokal aus keystore/keystore.properties, in GitHub Actions aus Secrets ----
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val ciKeystore = System.getenv("SIGNING_KEYSTORE_FILE")

android {
    namespace = "de.schee.wetter"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.schee.wetter"
        minSdk = 26
        targetSdk = 36
        versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
        versionName = "$versionMajor.$versionMinor.$versionPatch"
        buildConfigField("String", "UPDATE_REPO", "\"KvnStl924/wetter-app\"")
        // Nur 64-Bit-ARM (alle aktuellen Handys): hält die Karten-Bibliothek klein.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("wetter") {
            if (ciKeystore != null) {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file("keystore/" + File(keystoreProps.getProperty("storeFile")).name)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Beide Varianten mit demselben festen Schlüssel, damit jede APK ein Update der anderen sein kann.
        debug {
            signingConfig = signingConfigs.getByName("wetter")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("wetter")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("wetter.shots", System.getenv("WETTER_SCREENSHOTS") ?: "") }
    }
}

base.archivesName.set("Wetter-v$versionMajor.$versionMinor.$versionPatch")

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.compose.material:material-icons-core")
    // Karte im Vollbild-Radar (Version 12: passt zu Kotlin 2.0)
    implementation("org.maplibre.gl:android-sdk:12.3.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
