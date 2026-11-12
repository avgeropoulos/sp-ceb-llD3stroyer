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
        // NewPipe Extractor (YouTube search and streams) is published on JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "MixDeck"
include(":core")
include(":media")
// Desktop-only builds (-PdesktopOnly=true) skip the Android app, so they don't need the Android SDK.
if (providers.gradleProperty("desktopOnly").orNull != "true") include(":app")
include(":desktop")
