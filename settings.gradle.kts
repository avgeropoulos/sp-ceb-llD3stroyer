pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipe Extractor (reads YouTube audio for the song analysis) is published on JitPack.
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\..*") } }
    }
}

rootProject.name = "RoccosQuest"
include(":core")
include(":app")
include(":hero-core")
include(":bouzouki-core")
include(":bouzouki")
