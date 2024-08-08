import regenerativeag.BuildConstants

plugins {
    id("regenerativeag-application-plugin")
}

dependencies {
    implementation(project(":json-lib"))

    with(BuildConstants.DependencyVersions) {
        runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesCore")

        implementation("dev.kord:kord-core:$kord")
        implementation("dev.kord:kord-rest:$kord")
        implementation("dev.kord:kord-gateway:$kord")

        implementation("com.github.ajalt.clikt:clikt:$clikt")
    }

    with(BuildConstants.TestDependencyVersions) {
        testImplementation("org.junit.jupiter:junit-jupiter-params:$junit")
        testImplementation("io.mockk:mockk:$mockk")
    }
}