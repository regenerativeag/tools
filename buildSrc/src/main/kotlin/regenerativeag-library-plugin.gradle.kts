import regenerativeag.BuildConstants

plugins {
    kotlin("jvm")
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.oshai:kotlin-logging-jvm:${BuildConstants.DependencyVersions.kotlinLogging}")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(BuildConstants.DependencyVersions.jvm)
}