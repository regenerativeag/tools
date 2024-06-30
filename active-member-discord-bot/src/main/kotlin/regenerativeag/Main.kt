package regenerativeag

import io.ktor.client.*
import io.ktor.client.plugins.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val token = System.getenv("DISCORD_API_TOKEN")
    if (token.isNullOrEmpty()) {
        println("Please set the DISCORD_API_TOKEN env var")
    } else {
        run(token)
    }
}

private fun run(discordApiToken: String) {
    val httpClient = createHttpClient()
    val discord = Discord(httpClient, discordApiToken)
    val database = Database()
    val bot = ActiveMemberDiscordBot(database, discord)
    bot.login()
}

private fun createHttpClient() = HttpClient {
    expectSuccess = false
    install(HttpRequestRetry) {
        retryOnServerErrors(3)
        exponentialDelay()
    }
}