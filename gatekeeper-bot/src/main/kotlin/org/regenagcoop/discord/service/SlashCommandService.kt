package org.regenagcoop.discord.service

import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.createGuildChatInputCommand
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import mu.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.ChannelId
import org.regenagcoop.discord.model.MessageId
import org.regenagcoop.model.ActiveMemberConfig

/** Handles slash command registration and execution for moderator workflows. */
class SlashCommandService(
    discord: Discord,
    private val discordApiToken: String,
    private val activeMemberConfig: ActiveMemberConfig,
) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }
    private val kord = Kord(discordApiToken)

    private val postCommandName = normalizeSlashCommandName(activeMemberConfig.slashCommandConfig.postCommand)
    private val editCommandName = normalizeSlashCommandName(activeMemberConfig.slashCommandConfig.editCommand)

    suspend fun start() {
        registerCommands()
        attachHandlers()
        // Endlessly listen for slash command interactions.
        kord.login()
    }

    private suspend fun registerCommands() {
        val guildSnowflake = Snowflake(guildId)
        kord.createGuildChatInputCommand(guildSnowflake, postCommandName, "Post a message to a specific channel") {
            string("message", "Message content to post") {
                required = true
            }
            channel("channel", "Channel where the message will be posted") {
                required = true
            }
        }

        kord.createGuildChatInputCommand(guildSnowflake, editCommandName, "Edit an existing message by message link") {
            string("message", "New message content") {
                required = true
            }
            string("message_link", "Discord message link to edit") {
                required = true
            }
        }

        logger.info { "Registered slash commands '/$postCommandName' and '/$editCommandName' for guildId=$guildId" }
    }

    private fun attachHandlers() {
        kord.on<GuildChatInputCommandInteractionCreateEvent> {
            val command = interaction.command
            when (command.rootName) {
                postCommandName -> handlePostCommand(this)
                editCommandName -> handleEditCommand(this)
                else -> {
                    // no-op
                }
            }
        }
    }

    private suspend fun handlePostCommand(event: GuildChatInputCommandInteractionCreateEvent) {
        val response = event.interaction.deferPublicResponse()
        if (!event.isAdmin()) {
            response.respond {
                content = "Only admins can run this command."
            }
            return
        }

        val command = event.interaction.command
        val message = command.strings["message"]
        val channelId = command.channels["channel"]?.id?.value
        if (message.isNullOrBlank() || channelId == null) {
            response.respond {
                content = "Invalid command usage. Please provide both `message` and `channel`."
            }
            return
        }

        discord.rooms.postMessage(message = message, channelId = channelId)
        response.respond {
            content = "Posted message to <#$channelId>."
        }
    }

    private suspend fun handleEditCommand(event: GuildChatInputCommandInteractionCreateEvent) {
        val response = event.interaction.deferPublicResponse()
        if (!event.isAdmin()) {
            response.respond {
                content = "Only admins can run this command."
            }
            return
        }

        val command = event.interaction.command
        val message = command.strings["message"]
        val messageLink = command.strings["message_link"]
        if (message.isNullOrBlank() || messageLink.isNullOrBlank()) {
            response.respond {
                content = "Invalid command usage. Please provide both `message` and `message_link`."
            }
            return
        }

        val parsedMessageRef = parseMessageLink(messageLink)
        if (parsedMessageRef == null) {
            response.respond {
                content = "Invalid message link. Expected format like https://discord.com/channels/<guild>/<channel>/<message>."
            }
            return
        }

        if (parsedMessageRef.guildId != guildId) {
            response.respond {
                content = "That message link points to a different guild."
            }
            return
        }

        discord.rooms.editMessage(
            channelId = parsedMessageRef.channelId,
            messageId = parsedMessageRef.messageId,
            newText = message,
        )
        response.respond {
            content = "Edited message in <#${parsedMessageRef.channelId}>."
        }
    }

    private suspend fun GuildChatInputCommandInteractionCreateEvent.isAdmin(): Boolean {
        val member = interaction.user.asMember(Snowflake(guildId))
        val permissions = member.getPermissions()
        return permissions.contains(Permission.Administrator)
    }

    private fun normalizeSlashCommandName(configValue: String): String {
        val normalized = configValue.trim().removePrefix("/").lowercase()
        require(normalized.isNotBlank()) { "Slash command name cannot be blank. Config value='$configValue'" }
        return normalized
    }

    private fun parseMessageLink(link: String): ParsedMessageRef? {
        val regex = Regex(
            pattern = """^https?://(?:ptb\.|canary\.)?discord(?:app)?\.com/channels/(\d+)/(\d+)/(\d+)$""",
            option = RegexOption.IGNORE_CASE,
        )
        val match = regex.matchEntire(link.trim()) ?: return null
        return ParsedMessageRef(
            guildId = match.groupValues[1].toULongOrNull() ?: return null,
            channelId = match.groupValues[2].toULongOrNull() ?: return null,
            messageId = match.groupValues[3].toULongOrNull() ?: return null,
        )
    }

    private data class ParsedMessageRef(
        val guildId: ULong,
        val channelId: ChannelId,
        val messageId: MessageId,
    )
}
