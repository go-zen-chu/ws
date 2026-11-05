import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing comes from the environment so the keystore never lives in the repo.
// Release and preview use separate keys (WS_* and WS_PREVIEW_*) so PR CI never sees the release key.
// Without them a build falls back to the debug key (fine for local sideloading).
val signingEnvPrefixes = mapOf("release" to "WS_", "preview" to "WS_PREVIEW_")
val versionBase = providers.gradleProperty("ws.versionBase").get()

android {
    namespace = "xyz.amasuda.ws"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.amasuda.ws"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("WS_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("WS_VERSION_NAME") ?: "$versionBase.0-dev"
    }

    signingConfigs {
        signingEnvPrefixes.forEach { (name, prefix) ->
            val keystore = System.getenv("${prefix}KEYSTORE_FILE") ?: return@forEach
            create(name) {
                storeFile = file(keystore)
                storePassword = System.getenv("${prefix}KEYSTORE_PASSWORD")
                keyAlias = System.getenv("${prefix}KEY_ALIAS")
                keyPassword = System.getenv("${prefix}KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // The preview app for trying out PRs: installs next to the release app with its own data.
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-" + (System.getenv("WS_PREVIEW_LABEL") ?: "preview")
            // A fixed preview key so CI builds update the installed preview in place.
            signingConfig = signingConfigs.findByName("preview") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar only ships stubs of org.json, so JVM unit tests need the real one.
    testImplementation("org.json:json:20240303")
}
