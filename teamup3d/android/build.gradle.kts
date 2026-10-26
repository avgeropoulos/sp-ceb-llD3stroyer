import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.roccoquest.team3d"
    compileSdk = 35

    defaultConfig {
        // Its own app id, so Team Up! 3D installs next to Team Up! instead of replacing it.
        applicationId = "com.roccoquest.teamup3d"
        minSdk = 24
        targetSdk = 35
        // Every GitHub build gets a bigger number, so a new APK always installs as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(100) ?: 1
        // The branch this was built from: the UPDATE button gets newer builds of the same branch.
        buildConfigField("String", "BRANCH", "\"" + (System.getenv("GITHUB_REF_NAME") ?: "") + "\"")
        versionName = "3D 1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // The same fixed key as the other games: Android only installs an update over an app
    // signed with the same key, so updates never need an uninstall first.
    signingConfigs {
        create("games") {
            storeFile = rootProject.file("keystore/games.keystore")
            storePassword = "roccoquest"
            keyAlias = "games"
            keyPassword = "roccoquest"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("games")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("games")
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation(project(":team"))
    implementation(project(":teamup3d:engine"))
}
