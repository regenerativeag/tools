package org.regenagcoop

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import io.ktor.client.*
import io.ktor.client.plugins.*
import mu.KotlinLogging
import org.regenagcoop.discord.ActiveMemberDiscordBot
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.tools.GlobalObjectMapper
import java.io.File
import kotlin.system.exitProcess


class Main : CliktCommand() {
    private val logger = KotlinLogging.logger { }

    private val configPath: String by argument()
        .help("path to the configuration file")

    private val dryRun: Boolean by option()
        .boolean()
        .default(true)
        .help("set to false in order for changes to take effect")

    override fun run() {
        val discordApiToken = readEnvVar("DISCORD_API_TOKEN")
        logger.info("Launching with configPath=$configPath, dryRun=$dryRun")
        val httpClient = createHttpClient()
        val database = Database()
        val config = GlobalObjectMapper.readValue(File(configPath), ActiveMemberConfig::class.java)
        val bot = ActiveMemberDiscordBot(httpClient, discordApiToken, dryRun, database, config)
        bot.login()
    }

    private fun readEnvVar(name: String, required: Boolean = true): String {
        val value: String? = System.getenv(name)
        if (required && value.isNullOrBlank()) {
            println("Please set the $name environment variable")
            exitProcess(-1)
        }
        return value ?: ""
    }

    private fun createHttpClient() = HttpClient {
        expectSuccess = false
        install(HttpRequestRetry) {
            retryOnServerErrors(3)
            exponentialDelay()
        }
    }
}

fun main(args: Array<String>) = Main().main(args)