import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The 3D renderer for Team Up! 3D, in plain Kotlin (no Android), so it can be tested on any JVM.
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
    api(project(":team"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
