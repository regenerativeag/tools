package regenerativeag

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.boolean
import io.ktor.client.*
import io.ktor.client.plugins.*
import mu.KotlinLogging
import regenerativeag.discord.ActiveMemberDiscordBot
import regenerativeag.model.ActiveMemberConfig
import regenerativeag.tools.GlobalObjectMapper
import java.io.File


class Main : CliktCommand() {
    private val logger = KotlinLogging.logger { }

    private val configPath: String by argument()
        .help("path to the configuration file")

    private val dryRun: Boolean by option()
        .boolean()
        .default(true)
        .help("set to false in order for changes to take effect")

    private val discordApiToken: String by option(envvar="DISCORD_API_TOKEN")
        .required()

    override fun run() {
        logger.info("Launching with configPath=$configPath, dryRun=$dryRun, discordApiToken=<omitted>")
        val httpClient = createHttpClient()
        val database = Database()
        val config = GlobalObjectMapper.readValue(File(configPath), ActiveMemberConfig::class.java)
        val bot = ActiveMemberDiscordBot(httpClient, discordApiToken, dryRun, database, config)
        bot.login()
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