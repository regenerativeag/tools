package org.regenagcoop.discord

import dev.kord.gateway.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Clock
import mu.KotlinLogging
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.Reaction
import org.regenagcoop.discord.model.UserId

open class DiscordBot(
    discord: Discord,
    private val discordApiToken: String,
    private val onJoinedGuild: (suspend (UserId) -> Unit)? = null,
    private val onMessage: (suspend (Message) -> Unit)? = null,
    private val onReaction: (suspend (Reaction) -> Unit)? = null,
): DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    @OptIn(PrivilegedIntent::class)
    suspend fun login() {
        val gateway = DefaultGateway()

        if (onJoinedGuild != null) {
            gateway.events.filterIsInstance<GuildMemberAdd>().onEach { guildMemberAdd ->
                val user = guildMemberAdd.member.user.value!!
                usernameCache.cacheFrom(user)
                val userId = user.id.value
                onJoinedGuild.invoke(userId)
            }
        }

        if (onMessage != null) {
            gateway.events.filterIsInstance<MessageCreate>().onEach { messageCreate ->
                with(messageCreate.message) {
                    val channelName = channelNameCache.lookup(this.channelId.value)
                    val userId = this.getUserId()
                    val username = usernameCache.lookup(userId)
                    val localDate = this.getUtcDate()
                    logger.debug { "Message received from $username on $localDate in $channelName" }
                    onMessage.invoke(this.toMessage())
                }
            }.launchIn(gateway)
        }

        if (onReaction != null) {
            gateway.events.filterIsInstance<MessageReactionAdd>().onEach { reactionEvent ->
                val timestamp = Clock.System.now() // discord doesn't provide timestamps for reactions, so we are approximating it by grabbing the timestamp that we receive the event
                val reaction = Reaction(
                    reactionEvent.reaction.userId.value,
                    timestamp,
                    reactionEvent.reaction.messageId.value,
                    reactionEvent.reaction.emoji.name ?: ""
                )
                val username = usernameCache.lookup(reaction.userId)
                logger.debug { "Reaction received from $username on ${reaction.utcDate}: $reaction"}
                onReaction.invoke(reaction)
            }.launchIn(gateway)
        }

        // endlessly listen for events
        gateway.start(discordApiToken) {
            intents += Intent.GuildMembers
        }
    }
}