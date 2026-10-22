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
include(":walk")
include(":walkapp")
include(":walkshared")
include(":walk3d")
include(":walk3dapp")
