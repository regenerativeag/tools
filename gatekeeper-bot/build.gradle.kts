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
