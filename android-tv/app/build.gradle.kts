plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

/**
 * Short git SHA of the working tree, suffixed with "-dirty" when there are
 * uncommitted changes — an APK built from a dirty tree is not reproducible from
 * its SHA alone and must say so. "nogit" when git is unavailable (source
 * archive, no .git dir); the build still succeeds rather than failing hard.
 */
val gitShortSha: String = try {
    val sha = providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
    }.standardOutput.asText.get().trim()
    val dirty = providers.exec {
        commandLine("git", "status", "--porcelain")
    }.standardOutput.asText.get().isNotBlank()
    if (sha.isEmpty()) "nogit" else if (dirty) "$sha-dirty" else sha
} catch (_: Exception) {
    "nogit"
}

android {
    namespace = "app.ouie.screens"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ouie.screens"
        minSdk = 26              // Android TV 8.0 floor; current F&B TVs are newer
        targetSdk = 35
        versionCode = 10

        // versionName is what the TV reports as `screens.app_version` on every
        // heartbeat, so it MUST identify the build. It didn't: every build from
        // 2026-05-21 onward reported a static "1.0.0-ouie", which is why the
        // 2026-06/07 ESSEL Bogor outage couldn't be diagnosed from the
        // dashboard — there was no way to tell whether a stranded TV had the
        // identity-recovery fix (PR #11) or a pre-fix build. The short git SHA
        // makes each build distinguishable without an on-site `adb shell
        // dumpsys package`. Falls back to "nogit" for source-archive builds.
        versionName = "1.1.0-ouie+$gitShortSha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Baked at build time from env or gradle.properties (see README for override).
        // Defaults to ouie PROD; override locally with -PSUPABASE_URL=... for STG smoke.
        val supabaseUrl = (project.findProperty("SUPABASE_URL") as String?)
            ?: System.getenv("SUPABASE_URL")
            ?: "https://glvoigzqwtpgkemnaubo.supabase.co"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
    }

    signingConfigs {
        // Pre-launch deployment uses the Android debug keystore to sign release
        // builds. This unblocks sideload-and-go for the first PROD TV without
        // forcing a release-keystore provisioning step. Sunset condition: at
        // first non-owner onboard (see CLAUDE.md Project Status), generate a
        // dedicated release keystore (`keytool -genkey ...`) and switch this
        // block to read from a keystore.properties file. Switching keystores
        // requires uninstall+reinstall of any deployed APKs (Android refuses
        // in-place upgrades across signature mismatches); one re-pair is the
        // cost. For the current single-TV PROD deployment, that's acceptable.
        create("debugKeystore") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("debugKeystore")
            isMinifyEnabled = false                // no obfuscation in v1 — logs readable
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Without this, the stubbed android.jar makes every android.util.Log
            // call throw RuntimeException("Stub!"). That is not cosmetic: code
            // like PairingViewModel's `catch { Log.w(...); appState.toError(...) }`
            // has the Stub! exception escape the catch block, so a JVM test sees
            // the error state never being reached while a real device is fine.
            // It silently made the pairing failure path untestable off-device.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material3)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)

    // Media3 declared for 3b; unused here but pinned once.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
