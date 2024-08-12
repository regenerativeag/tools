package org.regenagcoop

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import mu.KotlinLogging
import org.regenagcoop.discord.ActiveMemberDiscordBot


class Main : CliktCommand() {
    private val logger = KotlinLogging.logger { }

    private val configPath: String by argument()
        .help("path to the configuration file")

    private val dryRun: Boolean by option()
        .boolean()
        .default(true)
        .help("set to false in order for changes to take effect")

    override fun run() {
        val bot = with (DependencyFactory()) {
            val discordApiToken = readDiscordApiToken()
            logger.info("Launching with configPath=$configPath, dryRun=$dryRun")
            val httpClient = createHttpClient()
            val database = createDatabase()
            val config = readConfig(configPath)
            ActiveMemberDiscordBot(httpClient, discordApiToken, dryRun, database, config)
        }

        bot.login()
    }
}

fun main(args: Array<String>) = Main().main(args)