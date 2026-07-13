pluginManagement {
    includeBuild("build-logic")

    repositories {
        if (System.getenv("CI") == "true") {
            maven {
                name = "googleMavenCentralMirror"
                url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
                mavenContent { releasesOnly() }
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "gradle-api"
