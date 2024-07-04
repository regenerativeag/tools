package regenerativeag

import io.ktor.client.*
import io.ktor.client.plugins.*
import mu.KotlinLogging
import regenerativeag.model.ActiveMemberConfig
import regenerativeag.tools.GlobalObjectMapper
import java.io.File

private val logger = KotlinLogging.logger { }

fun main(args: Array<String>) {
    val token = System.getenv("DISCORD_API_TOKEN")
    if (token.isNullOrEmpty()) {
        println("Please set the DISCORD_API_TOKEN env var")
    } else if (args.size != 1) {
        println("Please provide the path to the configuration file as a single command line argument")
    } else {
        val configPath = args[0]
        logger.debug { "Config path: $configPath" }
        run(token, configPath)
    }
}

private fun run(discordApiToken: String, configPath: String) {
    val httpClient = createHttpClient()
    val discord = Discord(httpClient, discordApiToken)
    val database = Database()
    val config = GlobalObjectMapper.readValue(File(configPath), ActiveMemberConfig::class.java)
    val bot = ActiveMemberDiscordBot(database, discord, config)
    bot.login()
}

private fun createHttpClient() = HttpClient {
    expectSuccess = false
    install(HttpRequestRetry) {
        retryOnServerErrors(3)
        exponentialDelay()
    }
}