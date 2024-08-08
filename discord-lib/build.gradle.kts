import regenerativeag.BuildConstants

plugins {
    id("regenerativeag-library-plugin")
}

dependencies {
    with(BuildConstants.DependencyVersions) {
        api("dev.kord:kord-core:$kord")
        implementation("dev.kord:kord-rest:$kord")
        implementation("dev.kord:kord-gateway:$kord")
    }
}