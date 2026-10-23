import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI stamps every build: the run number is the version code the in-app updater compares, and the
// release it publishes to is where the app looks for the next one (DESIGN.md §2.2).
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val repository = System.getenv("GITHUB_REPOSITORY").orEmpty()
val branch = System.getenv("GITHUB_REF_NAME").orEmpty()
val updateManifestUrl =
    if (repository.isNotEmpty() && branch.isNotEmpty() && System.getenv("GITHUB_EVENT_NAME") != "pull_request") {
        "https://github.com/$repository/releases/download/latest-$branch/thermopylae-update.json"
    } else {
        ""
    }

android {
    namespace = "com.thermopylae"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thermopylae"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        resValue("string", "update_manifest_url", updateManifestUrl)
    }

    buildFeatures {
        resValues = true
    }

    signingConfigs {
        // One fixed key for every build, so a new APK installs over the old one (keeping your
        // progress) instead of Android refusing it. It is a convenience key for this hobby project,
        // checked in on purpose; use a private key for any store release.
        create("stable") {
            storeFile = file("signing.keystore")
            storePassword = "thermopylae"
            keyAlias = "thermopylae"
            keyPassword = "thermopylae"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
