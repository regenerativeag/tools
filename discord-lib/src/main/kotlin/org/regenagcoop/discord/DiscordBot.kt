package org.regenagcoop.discord

import dev.kord.gateway.DefaultGateway
import dev.kord.gateway.MessageCreate
import dev.kord.gateway.start
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import mu.KotlinLogging
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.Reaction

open class DiscordBot(
    discord: Discord,
    private val discordApiToken: String,
    private val onMessage: (suspend (Message) -> Unit)? = null,
    private val onReaction: (suspend (Reaction) -> Unit)? = null,
): DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    suspend fun login() {
        val gateway = DefaultGateway()

        if (onMessage != null) {
            gateway.events.filterIsInstance<MessageCreate>().onEach { messageCreate ->
                with(messageCreate.message) {
                    val channelName = channelNameCache.lookup(this.channelId.value)
                    val userId = this.getUserId()
                    val username = usernameCache.lookup(userId)
                    val localDate = this.getUtcDate()
                    logger.debug { "Message received from $username on $localDate in $channelName" }
                    onMessage.invoke(Message(userId, this.timestamp, this.content))
                }
            }.launchIn(gateway)
        }

        // TODO #16: listen for reactions
        throw NotImplementedError()

        // endlessly listen for events
        gateway.start(discordApiToken) {
            // use defaults... nothing to do here.
        }
    }
}