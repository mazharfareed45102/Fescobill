import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PversionCode=<n> so every published APK installs as an update.
val appVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.fescobill.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fescobill.app"
        minSdk = 21
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.0.$appVersionCode"
        // Every phone sold in Pakistan is ARM; this keeps the OCR model's native code small.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        // One fixed key for local and CI builds, so a newer APK always installs over an older one.
        create("app") {
            storeFile = rootProject.file("keystore/fescobill.jks")
            storePassword = "fescobill"
            keyAlias = "fescobill"
            keyPassword = "fescobill"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // On-device text recognition, bundled in the APK (works offline, no Play download needed).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
