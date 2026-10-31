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
        // One fixed key for every GitHub build. Android only installs an update over an app
        // signed with the same key; the default debug key is different on every CI machine.
        // It's committed on purpose so CI and local builds can sign; it isn't a secret.
        create("shared") {
            storeFile = file("javelin.keystore")
            storePassword = "javelinstrike"
            keyAlias = "javelin"
            keyPassword = "javelinstrike"
        }
        // The private upload key for the Play Store, given to CI as repository secrets.
        // Without them the Play bundle is built unsigned.
        val playKey = System.getenv("PLAY_KEYSTORE_FILE")
        if (playKey != null && file(playKey).exists()) {
            create("play") {
                storeFile = file(playKey)
                storePassword = System.getenv("PLAY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PLAY_KEY_ALIAS")
                keyPassword = System.getenv("PLAY_KEY_PASSWORD")
            }
        }
    }

    // Two versions of the app:
    //  * github: installed from the GitHub release and updates itself from there.
    //  * play:   for the Google Play Store, which handles updates (self-updating isn't allowed).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            signingConfig = signingConfigs.getByName("shared")
        }
        create("play") {
            dimension = "store"
            signingConfig = signingConfigs.findByName("play")
        }
    }

    // Published builds are release builds; they take the signing key of their flavor.
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // Lint still runs with ./gradlew lint, but never stops a build from publishing.
        checkReleaseBuilds = false
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
