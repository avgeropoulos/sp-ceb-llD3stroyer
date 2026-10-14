import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bouzouki"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bouzouki"
        minSdk = 24
        targetSdk = 35
        // Every CI build gets a higher version number, so Android installs it as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    // A fixed key kept in the repository. Android only installs an update over the app
    // if it is signed with the same key, and the default debug key is different on
    // every CI machine. The key is not secret: it only keeps updates installable.
    signingConfigs {
        create("bouzouki") {
            storeFile = file("signing/bouzouki.jks")
            storePassword = "bouzouki"
            keyAlias = "bouzouki"
            keyPassword = "bouzouki"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("bouzouki")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bouzouki")
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
    implementation(project(":bouzouki-core"))
}
