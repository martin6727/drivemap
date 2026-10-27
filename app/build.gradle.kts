import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.drivemap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drivemap.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 38
        versionName = "1.38"
        ndk {
            // phone (S23 Ultra) + emulator; MapLibre's native lib is ~13 MB per ABI
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            // Optimised build (much smoother than debug). Signed with this PC's debug key so it
            // installs over the debug build without uninstalling.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // android.util.Log etc. return defaults in JVM tests (used by the relay test)
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Map rendering (vector tiles from OpenFreeMap, no API key)
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // HTTP for routing / search / camera data
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Convoy + shared radar reports over MQTT 5 (end-to-end encrypted payloads)
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")
    // Android Auto (navigation app: templates + our own map on the car screen)
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
