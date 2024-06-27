package regenerativeag

import io.ktor.client.*
import io.ktor.client.plugins.*
import kotlin.system.exitProcess


fun main(args: Array<String>) {
    val token = System.getenv("DISCORD_API_TOKEN")
    if (token.isNullOrEmpty()) {
        println("Please set the DISCORD_API_TOKEN env var")
        exitProcess(-1)
    } else {
        val httpClient = HttpClient {
            expectSuccess = false
            install(HttpRequestRetry) {
                retryOnServerErrors(3)
                exponentialDelay()
            }
        }
        val discord = Discord(httpClient, token)
        val database = Database()
        val bot = ActiveMemberDiscordBot(database, discord)
        bot.login()
    }
}