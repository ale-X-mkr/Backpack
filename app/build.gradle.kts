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
        versionCode = 32
        versionName = "1.6.0"
        buildConfigField("String", "ORS_KEY_1", "\"${localProp("ors.key1")}\"")
        buildConfigField("String", "ORS_KEY_2", "\"${localProp("ors.key2")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Release signing with your own keystore, kept OUTSIDE the project.
    // In local.properties (never committed):
    //   release.storeFile=/path/to/your.keystore
    //   release.storePassword=...
    //   release.keyAlias=...
    //   release.keyPassword=...
    // Without these lines the release APK is built unsigned (sign it yourself).
    val releaseStore = localProp("release.storeFile")
    if (releaseStore.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = localProp("release.storePassword")
                keyAlias = localProp("release.keyAlias")
                keyPassword = localProp("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Not shrunk/obfuscated in 1.0.0: MapLibre's native code is most of
            // the size anyway, and an untested R8 setup is a crash risk.
            isMinifyEnabled = false
            isDebuggable = false
            if (releaseStore.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        // 1.5.0: the APK you give to others: like release, but WITHOUT your
        // openrouteservice keys inside (each person gets their own free key
        // with the in-app guide). Android Studio: Build Variants -> "share".
        create("share") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("String", "ORS_KEY_1", "\"\"")
            buildConfigField("String", "ORS_KEY_2", "\"\"")
        }
    }

    // The release build's lint step (lintVitalAnalyzeRelease) crashes inside
    // lint itself ("Already disposed: MessageBus", LintClassLoaderBuildService):
    // AGP 8.5's lint and Kotlin 2.3 don't get along. It only checks for fatal
    // lint issues; skip it for release builds. Run lint by hand if wanted.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
