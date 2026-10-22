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
    // Shares the drawing interface, pixel-art helpers and chiptune synth with Rocco's Quest.
    api(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
