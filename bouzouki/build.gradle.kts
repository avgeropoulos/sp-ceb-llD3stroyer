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
        // NewPipe Extractor uses newer Java APIs than Android 7-12 have.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    androidResources {
        noCompress += "onnx"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":bouzouki-core"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
