package org.regenagcoop

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.tools.GlobalObjectMapper
import java.io.File
import kotlin.system.exitProcess

class DependencyFactory {
    fun createHttpClient() = HttpClient(CIO) {
        engine {
            maxConnectionsCount = 100
        }
        expectSuccess = false
        install(HttpRequestRetry) {
            retryOnServerErrors(3)
            exponentialDelay()
        }
    }

    fun createDatabase() = Database()

    fun readConfig(configPath: String): ActiveMemberConfig {
        return GlobalObjectMapper.readValue(File(configPath), ActiveMemberConfig::class.java)
    }

    fun readDiscordApiToken() = readEnvVar("DISCORD_API_TOKEN")

    private fun readEnvVar(name: String, required: Boolean = true): String {
        val value: String? = System.getenv(name)
        if (required && value.isNullOrBlank()) {
            println("Please set the $name environment variable")
            exitProcess(-1)
        }
        return value ?: ""
    }
}