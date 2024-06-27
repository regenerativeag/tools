//⚠ Important: Dependency Versions in this file may come out of sync with the `CSF` dependency object

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:1.9.23")
    implementation("com.github.johnrengelman:shadow:8.1.1")
}

kotlin {
    jvmToolchain(17)
}