import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val norfoldProperties = Properties().apply {
    val source = rootProject.file("norfold.properties")
    if (source.isFile) source.inputStream().use(::load)
}

fun publicConfig(name: String): String =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: norfoldProperties.getProperty(name).orEmpty()

fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.norfold.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.norfold.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", buildConfigString(publicConfig("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(publicConfig("SUPABASE_PUBLISHABLE_KEY")))
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", buildConfigString(publicConfig("GOOGLE_SERVER_CLIENT_ID")))
        buildConfigField("String", "GOOGLE_CLOUD_PROJECT_ID", buildConfigString(publicConfig("GOOGLE_CLOUD_PROJECT_ID")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", buildConfigString(publicConfig("FIREBASE_PROJECT_ID")))
        buildConfigField("String", "AUTH_CALLBACK", buildConfigString("norfold://auth/callback"))
        buildConfigField("String", "CALENDAR_CALLBACK", buildConfigString("norfold://integrations/calendar"))
        buildConfigField("String", "HOMEPAGE_URL", buildConfigString("https://sheikhti1205.github.io/Norfold/"))
        buildConfigField("String", "PRIVACY_URL", buildConfigString("https://sheikhti1205.github.io/Norfold/privacy.html"))
        buildConfigField("String", "TERMS_URL", buildConfigString("https://sheikhti1205.github.io/Norfold/terms.html"))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation(platform("io.github.jan-tennert.supabase:bom:3.6.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.ktor:ktor-client-okhttp:3.5.1")
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-messaging")
    ksp("androidx.room:room-compiler:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
