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
    // Shares the sounds, music and critter types with the 2D walk (and through it, core's Gfx).
    api(project(":walk"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
