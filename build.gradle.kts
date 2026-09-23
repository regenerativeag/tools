import org.regenagcoop.BuildConstants

allprojects {
    group = BuildConstants.group
    version = BuildConstants.version

    repositories {
        maven {
            name = "kordexMirror"
            url = uri("https://repo.kordex.dev/mirror")
        }
    }
}

println(
    """
        ${group}:${version}
        JVM: ${BuildConstants.DependencyVersions.jvm}
    """.trimIndent()
)
