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
    }
}

rootProject.name = "RoccosQuest"
include(":core")
include(":app")
include(":team")
include(":teamapp")
// Team Up! 3D: the same game, with its levels drawn in 3D (a separate app).
include(":teamup3d:engine")
include(":teamup3d:android")

// Standalone free-roaming adventure with its own Android app identity.
include(":openworld")
