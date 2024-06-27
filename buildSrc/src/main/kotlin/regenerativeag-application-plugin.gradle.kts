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