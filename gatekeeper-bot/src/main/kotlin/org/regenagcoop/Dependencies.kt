package org.regenagcoop

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import org.regenagcoop.discord.Discord
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.tools.GlobalObjectMapper
import java.io.File
import kotlin.system.exitProcess

class Dependencies(configPath: String, dryRun: Boolean) {

    val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            engine {
                maxConnectionsCount = 100
            }
            expectSuccess = false
            install(HttpRequestRetry) {
                retryOnServerErrors(3)
                exponentialDelay()
            }
        }
    }

    val activeMemberConfig: ActiveMemberConfig by lazy {
        GlobalObjectMapper.readValue(
            File(configPath),
            ActiveMemberConfig::class.java
        )
    }

    val discordApiToken: String by lazy {
        readEnvVar("DISCORD_API_TOKEN")
    }


    val discord: Discord by lazy {
        Discord(httpClient, activeMemberConfig.guildId, discordApiToken, dryRun)
    }


    val database: Database by lazy {
        Database(discord, activeMemberConfig)
    }


    private fun readEnvVar(name: String, required: Boolean = true): String {
        val value: String? = System.getenv(name)
        if (required && value.isNullOrBlank()) {
            println("Please set the $name environment variable")
            exitProcess(-1)
        }
        return value ?: ""
    }
}