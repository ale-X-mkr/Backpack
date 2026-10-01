plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.3: MapLibre Android 13.x is built with Kotlin 2.3, and
    // Kotlin can only read libraries up to one version newer than itself.
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}
