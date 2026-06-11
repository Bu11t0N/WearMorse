// Корневой файл настроек Gradle — указывает, какие модули входят в проект.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WearMorseMessenger"

// Модуль телефона (:app) и модуль часов (:wear)
include(":app")
include(":wear")
