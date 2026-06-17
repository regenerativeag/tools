package org.regenagcoop.discord

import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.createGuildChatInputCommand
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.guild.MemberJoinEvent
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.message.ReactionAddEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import mu.KotlinLogging
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.*
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ExecutionException

open class DiscordBot(
    discord: Discord,
    private val discordApiToken: String,
    private val onTopLevelError: (suspend (Exception) -> Unit),
    private val onJoinedGuild: (suspend (UserId) -> Unit)? = null,
    private val onMessage: (suspend (Message) -> Unit)? = null,
    private val onReaction: (suspend (Reaction) -> Unit)? = null,
    private val getSlashCommands: (suspend () -> List<SlashCommandDefinition>)? = null,
    private val onSlashCommand: (suspend (SlashCommandInteraction) -> Unit)? = null,
): DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    lateinit var kord: Kord
        private set

    init {
        require((getSlashCommands == null) == (onSlashCommand == null)) {
            "getSlashCommands and onSlashCommand must both be provided or both be null"
        }
    }

    suspend fun login() {
        kord = Kord(discordApiToken)

        getSlashCommands?.invoke()?.forEach { def ->
            kord.createGuildChatInputCommand(Snowflake(guildId), def.name, def.description) {
                def.options.forEach { opt ->
                    when (opt.type) {
                        SlashCommandOptionType.STRING -> string(opt.name, opt.description) { required = opt.required }
                        SlashCommandOptionType.CHANNEL -> channel(opt.name, opt.description) { required = opt.required }
                    }
                }
            }
        }

        if (onJoinedGuild != null) {
            kord.on<MemberJoinEvent> {
                try {
                    val userId = member.id.value
                    onJoinedGuild.invoke(userId)
                } catch (e: Exception) {
                    val wrapped = ExecutionException(
                        "Exception occurred while processing MemberJoinEvent for user ${member.id.value} (${member.username})",
                        e,
                    )
                    onTopLevelError(wrapped)
                }
            }
        }

        if (onMessage != null) {
            kord.on<MessageCreateEvent> {
                try {
                    val discordMessage = message
                    val author = discordMessage.author
                        ?: throw IllegalStateException("Message ${discordMessage.id.value} has no author")
                    val channelName = channelNameCache.lookup(discordMessage.channelId.value)
                    val userId = author.id.value
                    val username = usernameCache.lookup(userId)
                    val localDate = LocalDate.ofInstant(discordMessage.timestamp.toJavaInstant(), ZoneOffset.UTC)
                    logger.debug { "Message received from $username on $localDate in $channelName" }
                    onMessage.invoke(
                        Message(
                            channelId = discordMessage.channelId.value,
                            messageId = discordMessage.id.value,
                            userId = userId,
                            instant = discordMessage.timestamp,
                            text = discordMessage.content,
                        )
                    )
                } catch (e: Exception) {
                    val author = message.author
                    val wrapped = ExecutionException(
                        "Exception occurred while processing MessageCreateEvent for message ${message.id.value} in channel ${message.channelId.value} from user ${author?.id?.value} (${author?.username})",
                        e,
                    )
                    onTopLevelError(wrapped)
                }
            }
        }

        if (onReaction != null) {
            kord.on<ReactionAddEvent> {
                try {
                    val timestamp =
                        Clock.System.now() // discord doesn't provide timestamps for reactions, so we are approximating it by grabbing the timestamp that we receive the event
                    val reaction = Reaction(
                        userId = userId.value,
                        instant = timestamp,
                        messageId = messageId.value,
                        emoji = emoji.name,
                    )
                    val username = usernameCache.lookup(reaction.userId)
                    logger.debug { "Reaction received from $username on ${reaction.utcDate}: $reaction" }
                    onReaction.invoke(reaction)
                } catch (e: Exception) {
                    val username = try { usernameCache.lookup(userId.value) } catch (_: Exception) { "unknown" }
                    val wrapped = ExecutionException(
                        "Exception occurred while processing ReactionAddEvent of reaction ${emoji.name} on message $messageId in channel $channelId from user ${userId.value} ($username)",
                        e,
                    )
                    onTopLevelError(wrapped)
                }
            }
        }

        if (onSlashCommand != null) {
            kord.on<GuildChatInputCommandInteractionCreateEvent> {
                val interactionWrapper = SlashCommandInteraction(
                    commandName = interaction.command.rootName,
                    userId = interaction.user.id.value,
                    channelId = interaction.channelId.value,
                    stringOptions = interaction.command.strings,
                    channelOptions = interaction.command.channels.mapValues { it.value.id.value },
                    isAdmin = interaction.user.asMember(Snowflake(guildId)).getPermissions().contains(Permission.Administrator),
                    respond = { text, isError ->
                        val emoji = if (isError) "❌" else "✅"
                        interaction.deferPublicResponse().respond { content = "$emoji $text" }
                    }
                )
                onSlashCommand.invoke(interactionWrapper)
            }
        }

        // endlessly listen for events
        kord.login {
            @OptIn(PrivilegedIntent::class)
            intents += Intent.GuildMembers
            if (onMessage != null) {
                intents += Intent.GuildMessages
                @OptIn(PrivilegedIntent::class)
                intents += Intent.MessageContent
            }
            if (onReaction != null) {
                intents += Intent.GuildMessageReactions
            }
        }
    }
}
