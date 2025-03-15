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
import java.util.concurrent.ExecutionException

open class DiscordBot(
    discord: Discord,
    private val discordApiToken: String,
    private val onTopLevelError: (suspend (Exception) -> Unit),
    private val onJoinedGuild: (suspend (UserId) -> Unit)? = null,
    private val onMessage: (suspend (Message) -> Unit)? = null,
    private val onReaction: (suspend (Reaction) -> Unit)? = null,
): DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    suspend fun login() {
        val gateway = DefaultGateway()

        if (onJoinedGuild != null) {
            gateway.events.filterIsInstance<GuildMemberAdd>().onEach { guildMemberAdd ->
                try {
                    val user = guildMemberAdd.member.user.value!!
                    usernameCache.cacheFrom(user)
                    val userId = user.id.value
                    onJoinedGuild.invoke(userId)
                } catch(e: Exception) {
                    val user = guildMemberAdd.member.user.value
                    val wrapped = ExecutionException("Exception occurred while processing GuildMemberAdd event for user ${user?.id?.value} (${user?.username})", e)
                    onTopLevelError(wrapped)
                }
            }.launchIn(gateway)
        }

        if (onMessage != null) {
            gateway.events.filterIsInstance<MessageCreate>().onEach { messageCreate ->
                try {
                    with(messageCreate.message) {
                        val channelName = channelNameCache.lookup(this.channelId.value)
                        val userId = this.getUserId()
                        val username = usernameCache.lookup(userId)
                        val localDate = this.getUtcDate()
                        logger.debug { "Message received from $username on $localDate in $channelName" }
                        onMessage.invoke(this.toMessage())
                    }
                } catch (e: Exception) {
                    val user = messageCreate.message.member.value?.user?.value
                    val message = messageCreate.message
                    val wrapped = ExecutionException("Exception occurred while processing MessageCreate event for message ${message.id.value} in channel ${message.channelId.value} from user ${user?.id?.value} (${user?.username})", e)
                    onTopLevelError(wrapped)
                }
            }.launchIn(gateway)
        }

        if (onReaction != null) {
            gateway.events.filterIsInstance<MessageReactionAdd>().onEach { reactionEvent ->
                try {
                    val timestamp =
                        Clock.System.now() // discord doesn't provide timestamps for reactions, so we are approximating it by grabbing the timestamp that we receive the event
                    val reaction = Reaction(
                        reactionEvent.reaction.userId.value,
                        timestamp,
                        reactionEvent.reaction.messageId.value,
                        reactionEvent.reaction.emoji.name ?: ""
                    )
                    val username = usernameCache.lookup(reaction.userId)
                    logger.debug { "Reaction received from $username on ${reaction.utcDate}: $reaction" }
                    onReaction.invoke(reaction)
                } catch (e: Exception) {
                    val user = reactionEvent.reaction.member.value?.user?.value
                    val message = reactionEvent.reaction
                    val wrapped = ExecutionException("Exception occurred while processing MessageReactionAdd event of reaction ${message.emoji.name} on message ${message.messageId} in channel ${message.channelId} from user ${user?.id?.value} (${user?.username})", e)
                    onTopLevelError(wrapped)
                }
            }.launchIn(gateway)
        }

        // endlessly listen for events
        gateway.start(discordApiToken) {
            @OptIn(PrivilegedIntent::class)
            intents += Intent.GuildMembers
        }
    }
}