import org.regenagcoop.BuildConstants

plugins {
    id("regenerativeag-library-plugin")
}

dependencies {
    implementation(project(":coroutine-lib"))

    with(BuildConstants.DependencyVersions) {
        api("dev.kord:kord-core:$kord")
        implementation("dev.kord:kord-rest:$kord")
        implementation("dev.kord:kord-gateway:$kord")
    }
}