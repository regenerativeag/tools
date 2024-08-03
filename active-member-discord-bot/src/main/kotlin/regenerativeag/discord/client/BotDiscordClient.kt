package regenerativeag.discord.client

import dev.kord.gateway.DefaultGateway
import dev.kord.gateway.MessageCreate
import dev.kord.gateway.start
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import regenerativeag.Discord
import regenerativeag.discord.getLocalDate
import regenerativeag.discord.getUserId
import regenerativeag.model.Message

class BotDiscordClient(discord: Discord, private val token: String): DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    fun login(onMessage: (Message) -> Unit) {
        val gateway = DefaultGateway()

        gateway.events.filterIsInstance<MessageCreate>().onEach { messageCreate ->
            with(messageCreate.message) {
                val channelName = channelNameCache.lookup(this.channelId.value)
                val userId = this.getUserId()
                val username = usernameCache.lookup(userId)
                val localDate = this.getLocalDate()
                logger.debug { "Message received from $username on $localDate in $channelName" }
                onMessage(Message(userId, localDate))
            }
        }.launchIn(gateway)

        runBlocking {
            gateway.start(token) {
                // use defaults... nothing to do here.
            }
        }
    }
}