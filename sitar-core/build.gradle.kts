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
    // The notes are found by Μπουζούκι's song analysis; this module reads them as a sitar's.
    implementation(project(":bouzouki-core"))
    // The Android app supplies onnxruntime-android; the tests run the real pitch model on the JVM one.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}
