import regenerativeag.BuildConstants

allprojects {
    group = BuildConstants.group
    version = BuildConstants.version
}

println(
    """
        ${group}:${version}
        JVM: ${BuildConstants.DependencyVersions.jvm}
    """.trimIndent()
)