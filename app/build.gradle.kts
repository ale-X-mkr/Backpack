import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// openrouteservice keys live in local.properties (never in source):
//   ors.key1=...   ors.key2=...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String): String = (localProps.getProperty(name) ?: "").trim()

android {
    namespace = "com.backpack.app"
    // 35: newer AndroidX pulled in by MapLibre may require it.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.backpack.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.8.0"
        buildConfigField("String", "ORS_KEY_1", "\"${localProp("ors.key1")}\"")
        buildConfigField("String", "ORS_KEY_2", "\"${localProp("ors.key2")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX (open source, no Play Services). Google's Material library removed.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    // MapLibre Native (BSD-2), OpenGL ES build: widest device support.
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; use the real one.
    testImplementation("org.json:json:20240303")
}
