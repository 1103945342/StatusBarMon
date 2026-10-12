pluginManagement {
    repositories {
        maven { url = uri("https://api.xposed.info/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://api.xposed.info/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "StatusBarMon"
include(":app")
