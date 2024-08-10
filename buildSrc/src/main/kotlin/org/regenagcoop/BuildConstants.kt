package org.regenagcoop

object BuildConstants {
    val group = "org.regenerativeag"
    val version = "0.1"

    object DependencyVersions {
        val jvm = 17
        val slf4j = "2.0.3"
        val kotlinLogging = "5.1.0"
        val kotlinCoroutinesCore = "1.8.0"
        val kord = "0.13.1"
        val jackson = "2.17.1"
        val clikt = "4.4.0"
    }

    object TestDependencyVersions {
        val junit = "5.1.0"
        val mockk = "1.13.10"
    }
}