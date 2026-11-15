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
    // The accuracy harness also scores the line as Bouzouki Hero plays it (eval/GameScore.kt).
    testImplementation(project(":hero-core"))
}

tasks.test { useJUnitPlatform() }

// Developer tool: analyse a WAV like the app does (see AnalyzeSong.kt).
tasks.register<JavaExec>("analyzeSong") {
    description = "Analyses -PsongAudio=<wav> with -PsongModel=<mdx.onnx> into -PsongOut=<dir> (and -PsongStem=<wav>)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bouzouki.core.song.AnalyzeSongKt")
    maxHeapSize = "6g"
    args = listOf("songAudio", "songModel", "songOut", "songStem").map { (findProperty(it) ?: "").toString() }
}

// Developer tool: score the transcription on an evaluation set (see eval/Evaluator.kt and docs/eval-recordings.md).
tasks.register<JavaExec>("evalSet") {
    description = "Scores -PevalSet=<dir|synth> into -PevalOut=<dir> as -PevalLabel=<name>"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bouzouki.core.eval.EvaluatorKt")
    maxHeapSize = "6g"
    args = listOf("evalSet", "evalOut", "evalLabel", "evalOnly", "evalDump").map { (findProperty(it) ?: "").toString() }
}

// Developer tool: how a run already made plays in Bouzouki Hero, with other confidence floors (see eval/GameScore.kt).
tasks.register<JavaExec>("gameScore") {
    description = "The game score of -PgameNotes=<label>.notes.csv (a run's dump) with -PgameFloors=<0,0.1,...> for -PgameOnly=<regex>"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bouzouki.core.eval.GameScoreTool")
    args = listOf("gameNotes", "gameFloors", "gameOnly").map { (findProperty(it) ?: "").toString() }
}
