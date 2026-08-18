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

val ciSigningVariableNames = listOf(
    "ANDROID_CI_KEYSTORE_PATH",
    "ANDROID_CI_KEYSTORE_PASSWORD",
    "ANDROID_CI_KEY_ALIAS",
    "ANDROID_CI_KEY_PASSWORD",
)
val ciSigningValues = ciSigningVariableNames.associateWith { name ->
    providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
}
val ciSigningConfigured = ciSigningValues.values.all { it != null }
val ciSigningPartiallyConfigured = ciSigningValues.values.any { it != null } && !ciSigningConfigured

if (ciSigningPartiallyConfigured) {
    val missing = ciSigningValues.filterValues { it == null }.keys.sorted().joinToString()
    throw GradleException("Android CI signing is incomplete; missing environment variables: $missing")
}

val ciSigningKeystore = ciSigningValues["ANDROID_CI_KEYSTORE_PATH"]?.let(rootProject::file)
if (ciSigningConfigured && ciSigningKeystore?.isFile != true) {
    throw GradleException("Android CI signing keystore does not exist at the configured path")
}

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
        buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", buildConfigString(publicConfig("GOOGLE_ANDROID_CLIENT_ID")))
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", buildConfigString(publicConfig("GOOGLE_SERVER_CLIENT_ID")))
        buildConfigField("String", "GOOGLE_CLOUD_PROJECT_ID", buildConfigString(publicConfig("GOOGLE_CLOUD_PROJECT_ID")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", buildConfigString(publicConfig("FIREBASE_PROJECT_ID")))
        buildConfigField("String", "KLIPY_API_KEY", buildConfigString(publicConfig("KLIPY_API_KEY")))
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

    signingConfigs {
        if (ciSigningConfigured) {
            create("ciDevelopment") {
                storeFile = ciSigningKeystore
                storePassword = ciSigningValues.getValue("ANDROID_CI_KEYSTORE_PASSWORD")
                keyAlias = ciSigningValues.getValue("ANDROID_CI_KEY_ALIAS")
                keyPassword = ciSigningValues.getValue("ANDROID_CI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ciSigningConfigured) {
                signingConfig = signingConfigs.getByName("ciDevelopment")
            }
            // PRE_BETA only: schema mismatches may reset developer/test data. This must be false
            // before any Beta/RC build leaves the development environment.
            buildConfigField("boolean", "ALLOW_DESTRUCTIVE_SCHEMA_RESET", "true")
        }
        release {
            buildConfigField("boolean", "ALLOW_DESTRUCTIVE_SCHEMA_RESET", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName(if (ciSigningConfigured) "ciDevelopment" else "debug")
            matchingFallbacks += listOf("release")
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

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
    // The BOM must not be older than what the graph resolves, or it stops governing anything. The
    // com.composables icon packs below depend on org.jetbrains.compose.foundation 1.9.3, which maps
    // onto androidx.compose.foundation 1.9.4; under 2024.12.01 (foundation 1.7.6) Gradle resolved
    // that conflict in favour of 1.9.4 and left material3 behind at the BOM's 1.3.1 — a material3
    // built against foundation 1.7.x running on 1.9.4. 2025.11.00 declares the 1.9.4 the graph
    // already picked and pairs it with material3 1.4.0, so the declared versions are the real ones.
    implementation(platform("androidx.compose:compose-bom:2025.11.00"))
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
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("com.composables:icons-lucide-cmp:2.2.1")
    implementation("com.composables:icons-tabler-outline-cmp:2.2.1")
    implementation("com.composables:icons-feather-cmp:2.2.1")
    implementation("com.composables:icons-bootstrap-outline-cmp:2.2.1")
    implementation("com.composables:icons-heroicons-outline-cmp:2.2.1")
    implementation("com.composables:icons-radix-cmp:2.2.1")
    implementation("com.composables:icons-font-awesome-solid-cmp:2.2.1")
    implementation("androidx.browser:browser:1.8.0")
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
    // Collaboration transport: explicit OkHttp for the Cloudflare Durable Object WebSocket adapter
    // (ktor-client-okhttp already pulls OkHttp transitively; pinned here for the WebSocket API).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Collaboration transport: native Ably Pub/Sub Android SDK. Auth is via short-lived scoped
    // tokens only (authCallback) — never an embedded API key.
    implementation("io.ably:ably-android:1.2.50")
    implementation("org.jetbrains:markdown:0.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Apache-2.0. Vetted AEAD/HPKE primitives behind Norfold's replaceable CryptoEngine.
    implementation("com.google.crypto.tink:tink:1.23.0")
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-messaging")
    ksp("androidx.room:room-compiler:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    // Compose behavior that JVM logic cannot prove — a field genuinely taking focus, a tap reaching the row
    // instead of the text field, a drop marker actually drawn — is asserted with the Compose test rule.
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.11.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Espresso 3.7 replaces the removed reflective InputManager.getInstance path with
    // Context.getSystemService, allowing Compose UI tests to run on current Android releases.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
