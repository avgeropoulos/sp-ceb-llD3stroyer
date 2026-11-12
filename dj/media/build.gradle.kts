import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Song sources shared by the phone and desktop apps: YouTube search and download,
// Spotify link import, and the on-disk track library.
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
    api(project(":core"))
    api("com.github.TeamNewPipe:NewPipeExtractor:v0.27.0")
    // Android has org.json built in; the desktop app adds the library itself.
    compileOnly("org.json:json:20250517")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20250517")
}

tasks.test { useJUnitPlatform() }
