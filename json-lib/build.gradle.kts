import regenerativeag.BuildConstants

plugins {
    id("regenerativeag-library-plugin")
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-core:${BuildConstants.DependencyVersions.jackson}")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:${BuildConstants.DependencyVersions.jackson}")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${BuildConstants.DependencyVersions.jackson}")
}