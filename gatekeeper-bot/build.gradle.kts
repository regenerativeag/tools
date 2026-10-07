import org.regenagcoop.BuildConstants

plugins {
    id("regenerativeag-application-plugin")
}

dependencies {
    implementation(project(":coroutine-lib"))
    implementation(project(":discord-lib"))
    implementation(project(":json-lib"))

    with(BuildConstants.DependencyVersions) {
        runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesCore")

        implementation("com.github.ajalt.clikt:clikt:$clikt")

        // Import Ktor BOM to align versions automatically
        implementation(platform("io.ktor:ktor-bom:$ktor"))
        implementation("io.ktor:ktor-client-core")
        implementation("io.ktor:ktor-client-cio")
    }

    with(BuildConstants.TestDependencyVersions) {
        testImplementation("org.junit.jupiter:junit-jupiter-params:$junit")
        testImplementation("io.mockk:mockk:$mockk")
    }
}

tasks.test {
    testLogging {
        // Show standard out and standard error in the console
        showStandardStreams = true

        // Optional: Customize which events are logged
        events("failed")

        // Optional: Show full stack traces for exceptions
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

