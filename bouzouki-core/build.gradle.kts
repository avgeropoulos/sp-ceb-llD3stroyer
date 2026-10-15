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

// Developer tool: analyse a WAV like the app does (used by .github/workflows/analyze-song.yml).
tasks.register<JavaExec>("analyzeSong") {
    description = "Analyses -PsongAudio=<wav> with -PsongModel=<mdx.onnx> into -PsongOut=<dir> (and -PsongStem=<wav>)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bouzouki.core.song.AnalyzeSongKt")
    maxHeapSize = "6g"
    args = listOf("songAudio", "songModel", "songOut", "songStem").map { (findProperty(it) ?: "").toString() }
}
