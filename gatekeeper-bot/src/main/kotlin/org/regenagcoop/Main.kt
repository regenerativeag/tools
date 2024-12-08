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
        val dependencies = Dependencies(configPath, dryRun)
        val bot = with (dependencies) {
            logger.info("Launching with configPath=$configPath, dryRun=$dryRun")
            ActiveMemberDiscordBot(httpClient, discordApiToken, dryRun, database, activeMemberConfig)
        }

        bot.start()
    }
}

fun main(args: Array<String>) = Main().main(args)