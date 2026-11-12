import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// MixDeck for Mac, Windows and Linux: the same engine, console and song sources as the phone
// app, in a desktop window. CI packages it per OS with jpackage (see .github/workflows/mixdeck.yml).
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val buildNumber = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// FFmpeg (for decoding and encoding audio) ships native code; bundle only this computer's.
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val platform = when {
    osName.contains("win") -> "windows-x86_64"
    osName.contains("mac") -> if (osArch == "aarch64" || osArch == "arm64") "macosx-arm64" else "macosx-x86_64"
    else -> if (osArch == "aarch64" || osArch == "arm64") "linux-arm64" else "linux-x86_64"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":media"))
    implementation("org.json:json:20250517")
    implementation("com.formdev:flatlaf:3.8")
    implementation("org.bytedeco:javacv:1.5.14") { isTransitive = false }
    implementation("org.bytedeco:javacpp:1.5.14")
    implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14")
    runtimeOnly("org.bytedeco:javacpp:1.5.14:$platform")
    runtimeOnly("org.bytedeco:ffmpeg:8.1.2-1.5.14:$platform")
}

val versionDir = layout.buildDirectory.dir("generated/version")
val writeVersion = tasks.register("writeVersion") {
    val out = versionDir
    val code = buildNumber
    val plat = platform
    inputs.property("code", code)
    outputs.dir(out)
    doLast {
        out.get().file("mixdeck-version.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("versionCode=$code\nversionName=1.$code\nplatform=$plat\n")
        }
    }
}
sourceSets["main"].resources.srcDir(writeVersion)

application {
    mainClass.set("com.mixdeck.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx3g", "-Dsun.java2d.uiScale.enabled=true")
}

// `./gradlew :desktop:selftest` checks FFmpeg, the engine and drawing work on this computer.
tasks.register<JavaExec>("selftest") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.mixdeck.desktop.MainKt")
    args("--selftest", layout.buildDirectory.dir("selftest").get().asFile.path)
    systemProperty("java.awt.headless", "true")
}

// `./gradlew :desktop:icons` draws the app icons for packaging.
tasks.register<JavaExec>("icons") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.mixdeck.desktop.MainKt")
    args("--make-icons", layout.buildDirectory.dir("icons").get().asFile.path)
    systemProperty("java.awt.headless", "true")
}
