import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.javelinstrike"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.javelinstrike"
        minSdk = 24
        targetSdk = 35
        // GitHub Actions sets BUILD_NUMBER, so every published build has a higher version
        // than the last one. The app compares it with the newest build to offer updates.
        val build = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 3
        versionCode = build
        versionName = "2.1 (build $build)"
    }

    signingConfigs {
        // One fixed key for every build. Android only installs an update over an app signed
        // with the same key; the default debug key is different on every CI machine.
        // It's committed on purpose so CI and local builds can sign; it isn't a secret.
        create("shared") {
            storeFile = file("javelin.keystore")
            storePassword = "javelinstrike"
            keyAlias = "javelin"
            keyPassword = "javelinstrike"
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
    implementation(project(":javelin-core"))
}
