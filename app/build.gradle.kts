import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the environment so the keystore never lives in the repo.
// Without it the release build falls back to the debug key (fine for local sideloading).
val releaseKeystore: String? = System.getenv("WS_KEYSTORE_FILE")

android {
    namespace = "com.gozenchu.ws"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gozenchu.ws"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("WS_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("WS_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("WS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WS_KEY_ALIAS")
                keyPassword = System.getenv("WS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
