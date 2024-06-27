import regenerativeag.BuildConstants

plugins {
    id("regenerativeag-application-plugin")
}

dependencies {
    implementation("dev.kord:kord-core:${BuildConstants.DependencyVersions.kord}")
    implementation("dev.kord:kord-rest:${BuildConstants.DependencyVersions.kord}")
    implementation("dev.kord:kord-gateway:${BuildConstants.DependencyVersions.kord}")
    testImplementation("org.junit.jupiter:junit-jupiter-params:${BuildConstants.TestDependencyVersions.junit}")
}