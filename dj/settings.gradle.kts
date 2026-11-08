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
include(":app")
