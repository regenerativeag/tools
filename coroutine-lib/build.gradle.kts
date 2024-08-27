import org.regenagcoop.BuildConstants

plugins {
    id("regenerativeag-library-plugin")
}

dependencies {
    with(BuildConstants.DependencyVersions) {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesCore")
    }
}