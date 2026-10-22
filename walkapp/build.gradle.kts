import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.herculesapollo.walkapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.herculesapollo.walk"
        minSdk = 24
        targetSdk = 35
        // Each CI build gets a higher version number so phones see it as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
    }

    // Every build is signed with this one key (kept in the repo, it's not a secret). Android
    // only installs an update signed with the same key as the app already on the phone, and
    // CI machines would otherwise make a new random debug key on every build.
    signingConfigs {
        create("shared") {
            storeFile = file("signing.keystore")
            storePassword = "android"
            keyAlias = "walk"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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

dependencies {
    implementation(project(":walk"))
}
