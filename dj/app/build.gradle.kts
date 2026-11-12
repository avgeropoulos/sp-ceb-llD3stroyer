import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PversionCode=<run number> so every build is newer than the last one,
// which is what the in-app updater compares against.
val buildNumber = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.mixdeck"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mixdeck"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        buildConfigField("String", "UPDATE_URL",
            "\"https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/mixdeck-latest/\"")
    }

    // Releases are signed with one fixed key: Android only installs an update over the app if
    // it's signed with the same key. The key isn't in git; CI writes it from the repository
    // secrets MIXDECK_KEYSTORE (base64) and MIXDECK_KEYSTORE_PASSWORD. Local builds without
    // it use the debug key.
    val keystore = file(System.getenv("MIXDECK_KEYSTORE_FILE") ?: "mixdeck.jks")
    val keyPassword = System.getenv("MIXDECK_KEYSTORE_PASSWORD")
    val release = keystore.exists() && keyPassword != null
    signingConfigs {
        if (release) create("mixdeck") {
            storeFile = keystore
            storePassword = keyPassword
            keyAlias = "mixdeck"
            this.keyPassword = keyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (release) "mixdeck" else "debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/**")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":media"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
