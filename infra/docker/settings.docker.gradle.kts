pluginManagement {
    val springBootVersion: String by settings

    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }

    plugins {
        id("org.springframework.boot") version springBootVersion
    }
}

rootProject.name = "cartogra"

val dockerService = System.getProperty("docker.service")
    ?: throw GradleException("Missing required system property: docker.service")

include(
    "shared:common",
    "shared:test-support",
    "services:$dockerService"
)
