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

// Both Google plugins are conditional on the same file, and the condition is not a convenience.
// `google-services.json` is gitignored — it carries this project's Firebase identifiers — so a fresh
// clone does not have one, and an unconditional `apply` fails such a clone at configuration time with
// "File google-services.json is missing". The Crashlytics plugin has the same shape of dependency: it
// uploads a mapping file to a Firebase project, and with no project configured there is nothing for it
// to upload to. Keeping both behind one `isFile` check means the open-source build path stays green and
// the two plugins can never disagree about whether Firebase is configured.
//
// What the Crashlytics plugin does, and why it is not optional once the dependency is present: a release
// build is minified, so every stack frame Crashlytics reports arrives obfuscated. The plugin uploads R8's
// mapping file so the console can deobfuscate. Without it the crash reports still arrive and are close to
// useless. It is a build-time uploader only — it adds no runtime collection of its own, and it cannot
// switch collection on. That is the consent gate's job, in NorfoldApplication.
if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.norfold.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.norfold.app"
        minSdk = 26
        targetSdk = 36
        // Bumped by hand, one step per release that leaves this machine. There is deliberately no
        // derivation from a tag or a commit count: `versionCode` is the one number Play treats as
        // permanent — a rollback or a re-run of CI must never be able to reissue one — and a
        // computed value is exactly what silently reissues after a squash, a re-tag, or a shallow
        // clone. `versionName` carries the pre-release suffix so a tester reading About can say
        // which alpha they are holding; `versionCode` does not encode it, because Play compares
        // integers and `alpha.3` is not one.
        versionCode = 2
        versionName = "0.1.0-alpha.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", buildConfigString(publicConfig("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(publicConfig("SUPABASE_PUBLISHABLE_KEY")))
        buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", buildConfigString(publicConfig("GOOGLE_ANDROID_CLIENT_ID")))
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", buildConfigString(publicConfig("GOOGLE_SERVER_CLIENT_ID")))
        buildConfigField("String", "GOOGLE_CLOUD_PROJECT_ID", buildConfigString(publicConfig("GOOGLE_CLOUD_PROJECT_ID")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", buildConfigString(publicConfig("FIREBASE_PROJECT_ID")))
        buildConfigField("String", "KLIPY_API_KEY", buildConfigString(publicConfig("KLIPY_API_KEY")))
        // No AUTH_CALLBACK or CALENDAR_CALLBACK constant. Both were declared in the initial
        // commit and never read by a single line of Kotlin, but neither URI is dead, so the
        // coupling is written here rather than lost with the field:
        //
        //   norfold://auth/callback       is `site_url` and the sole `additional_redirect_urls`
        //                                 entry in supabase/config.toml. Email sign-in is OTP
        //                                 codes and Google is CredentialManager, so nothing
        //                                 returns through a browser and the app never needs the
        //                                 string — but changing the scheme or applicationId
        //                                 means changing that file too.
        //   norfold://integrations/calendar  is what supabase/functions/calendar-callback
        //                                 redirects to on both success and failure, and the
        //                                 manifest already registers an intent-filter for it.
        //                                 The flow that would reach that function does not
        //                                 exist yet on either side.
        // The published site moved with the repository to the Norfold-App organisation. The old
        // user-site host serves 404 for every one of these paths, so anything still pointing there
        // Keep these on the public Pages deployment; the product repository itself is private.
        buildConfigField("String", "HOMEPAGE_URL", buildConfigString("https://norfold.pages.dev/"))
        buildConfigField("String", "PRIVACY_URL", buildConfigString("https://norfold.pages.dev/privacy.html"))
        buildConfigField("String", "TERMS_URL", buildConfigString("https://norfold.pages.dev/terms.html"))
        buildConfigField("String", "SUPPORT_URL", buildConfigString("https://norfold.pages.dev/support.html"))
        buildConfigField("String", "LICENSE_URL", buildConfigString("https://norfold.pages.dev/license.html"))
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
            // There is deliberately no `signingConfig` here, and its absence is the policy rather
            // than an omission to be tidied up later. The production signing identity does not
            // exist yet and will be generated on Linux, not in this WSL environment: a key created
            // under a filesystem bridged from Windows is one whose durability and permissions
            // nobody can vouch for, and an upload key cannot be rotated once Play has seen it.
            // So `assembleRelease` produces an unsigned artefact on purpose. `bundleRelease` in CI
            // exercises R8, resource shrinking and the manifest merge — everything that can break
            // in a release build except the signature — which is the part worth gating on now.
            // Signing arrives as one deliberate step at the clean-room migration, with the keystore
            // and its passwords held outside the repository. Do not point this at `ciDevelopment`;
            // that key is a throwaway for installing test builds, and shipping under it would
            // permanently bind the application id to a credential that lives in CI settings.
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
    // Version comes from the BOM above (34.16.0 pins crashlytics 20.1.0). Automatic upload is OFF until
    // the user turns diagnostics on: NorfoldApplication calls setCrashlyticsCollectionEnabled from the
    // DiagnosticsStore preference before Crashlytics can initialise, and the manifest carries
    // firebase_crashlytics_collection_enabled=false so a first launch cannot report before being asked.
    // Deliberately not added: firebase-crashlytics-ndk. Norfold ships no native code of its own, so it
    // would only symbolise crashes inside third-party .so files — reports nobody here can act on.
    implementation("com.google.firebase:firebase-crashlytics")
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
