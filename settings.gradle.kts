pluginManagement {
    repositories {
        maven("https://dl.google.com/dl/android/maven2/") {
            name = "Google"
        }
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
rootProject.name = "CalOnyx"
include(":app")
