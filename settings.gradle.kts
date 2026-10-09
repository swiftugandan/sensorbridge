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

rootProject.name = "sensorbridge"

include(":app")
include(":core:domain")
include(":core:sensors")
include(":core:audio")
include(":core:camera")
include(":tilt:engine")
include(":tilt:app")
