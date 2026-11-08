import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes the workflow run number, so every published build has a higher version
// and the in-game updater can tell when a newer one is out.
val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.goalrush"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.goalrush"
        minSdk = 24
        targetSdk = 35
        versionCode = build
        versionName = "1.0.$build"
    }

    signingConfigs {
        // A fixed key checked into the repository, so each new build installs over the
        // previous one (the in-game update needs every build signed with the same key).
        create("goalrush") {
            storeFile = file("goalrush.keystore")
            storePassword = "goalrush"
            keyAlias = "goalrush"
            keyPassword = "goalrush"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("goalrush") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("goalrush")
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
    implementation(project(":soccer-core"))
}
