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

// Pocket Critters: a Game Boy Color style monster-collecting RPG.
include(":critters-core")
project(":critters-core").projectDir = file("critters/core")
include(":critters-app")
project(":critters-app").projectDir = file("critters/app")
