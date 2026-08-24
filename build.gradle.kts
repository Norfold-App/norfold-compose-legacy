plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    // Uploads R8's mapping file so release crash reports deobfuscate. Applied in the app module only
    // when google-services.json exists; see the comment there. Version 3 requires AGP 8.1+ and
    // Gradle 8.0+, both of which this build exceeds (AGP 8.13.2, wrapper 9.4.1).
    id("com.google.firebase.crashlytics") version "3.0.8" apply false
}
