//⚠ Important: Dependency Versions in this file may come out of sync with the `CSF` dependency object

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.4.20")
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:9.6.1")
}

kotlin {
    jvmToolchain(17)
}
