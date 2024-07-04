import regenerativeag.BuildConstants

private val mainClassName = "regenerativeag.MainKt"

plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow")
}

application {
    mainClass.set(mainClassName)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-simple:${BuildConstants.DependencyVersions.slf4j}")
    implementation("io.github.oshai:kotlin-logging-jvm:${BuildConstants.DependencyVersions.kotlinLogging}")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(BuildConstants.DependencyVersions.jvm)
}

tasks.jar {
    manifest.attributes["Main-Class"] = mainClassName
}

tasks.run<JavaExec> {
    // pass properties provided to gralde command via -D flag to the JVM process that runs the application
    systemProperties(System.getProperties() as Map<String, Any>)
}