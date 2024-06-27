package regenerativeag

object BuildConstants {
    val group = "regenerativeag"
    val version = "0.1"

    object DependencyVersions {
        val jvm = 17
        val kotlinCoroutinesCore = "1.8.0"
        val kord = "0.13.1"
        val ktor = "2.3.10"
        val jacksonModuleKotlin = "2.17.0"
    }

    object TestDependencyVersions {
        val junit = "5.1.0"
        val mockk = "1.13.10"
    }
}