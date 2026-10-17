import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bouzouki"
    compileSdk = 35

    defaultConfig {
        // The Lab version: its own app on the phone, installed beside the original Μπουζούκι (com.bouzouki).
        applicationId = "com.bouzouki.lab"
        // alphaTab (the tab sheet and its player) needs Android 8.0.
        minSdk = 26
        targetSdk = 35
        // Every CI build gets a higher version number, so Android installs it as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
        // The song analysis runs ONNX models; only 64-bit ARM phones keep the APK small.
        ndk { abiFilters += "arm64-v8a" }
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

    packaging {
        // Keep the ONNX Runtime library compressed in the APK (it's unpacked at install):
        // the download is about half the size.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    androidResources {
        noCompress += listOf("onnx", "tflite")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":bouzouki-core"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    // LiteRT (Google's TensorFlow Lite runtime, Apache-2.0): the other engine for Basic Pitch (see PitchEngines).
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    // alphaTab (MPL-2.0): tablature display and playback; with alphaSkia (BSD-3-Clause) and AndroidX (Apache-2.0).
    implementation("net.alphatab:alphaTab:1.8.4")
}
