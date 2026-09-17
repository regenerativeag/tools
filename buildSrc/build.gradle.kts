//⚠ Important: Dependency Versions in this file may come out of sync with the `CSF` dependency object

plugins {
    `kotlin-dsl`
     id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.4.20")
}

kotlin {
    jvmToolchain(17)
}
