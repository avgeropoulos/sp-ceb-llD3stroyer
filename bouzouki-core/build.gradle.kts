import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The Android app supplies onnxruntime-android, which has the same ai.onnxruntime API.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.31.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
