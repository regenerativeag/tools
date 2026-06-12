package org.regenagcoop.discord.service

import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.createGuildChatInputCommand
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import mu.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.DiscordBot
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.client.RoomsDiscordClient
import org.regenagcoop.model.ActiveMemberConfig

/** Handles slash command registration and execution for moderator workflows. */
class SlashCommandService(
    discord: Discord,
    discordBot: DiscordBot,
    private val roomsDiscordClient: RoomsDiscordClient,
    private val activeMemberConfig: ActiveMemberConfig,
) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }
    private val discordBot = discordBot

    private val postCommandName = normalizeSlashCommandName(activeMemberConfig.slashCommandConfig.postCommand)
    private val editCommandName = normalizeSlashCommandName(activeMemberConfig.slashCommandConfig.editCommand)

    fun configure() {
        discordBot.configureSlashCommands(
            registration = ::registerCommands,
            onSlashCommand = ::handleSlashCommand,
        )
    }

    private suspend fun registerCommands(kord: Kord) {
        val guildSnowflake = Snowflake(guildId)
        kord.createGuildChatInputCommand(guildSnowflake, postCommandName, "Post a copy of a linked message") {
            string("source_message_link", "Discord message link to copy content from") {
                required = true
            }
            channel("channel", "Channel where the message will be posted") {
                required = true
            }
        }

        kord.createGuildChatInputCommand(guildSnowflake, editCommandName, "Edit a message to match another linked message") {
            string("source_message_link", "Discord message link to copy content from") {
                required = true
            }
            string("target_message_link", "Discord message link to edit") {
                required = true
            }
        }

        logger.info { "Registered slash commands '/$postCommandName' and '/$editCommandName' for guildId=$guildId" }
    }

    private suspend fun handleSlashCommand(event: GuildChatInputCommandInteractionCreateEvent) {
        val command = event.interaction.command
        when (command.rootName) {
            postCommandName -> handlePostCommand(event)
            editCommandName -> handleEditCommand(event)
            else -> {
                // no-op
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
        val sourceMessageLink = command.strings["source_message_link"]
        val channelId = command.channels["channel"]?.id?.value
        if (sourceMessageLink.isNullOrBlank() || channelId == null) {
            response.respond {
                content = "Invalid command usage. Please provide both `source_message_link` and `channel`."
            }
            return
        }

        val sourceMessage = roomsDiscordClient.getMessageFromUrl(sourceMessageLink)
        if (sourceMessage == null) {
            response.respond {
                content = "Invalid source message link or message could not be fetched."
            }
            return
        }

        roomsDiscordClient.postMessage(message = sourceMessage.text, channelId = channelId)
        response.respond {
            content = "Posted message to <#$channelId> using content from the source message."
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
        val sourceMessageLink = command.strings["source_message_link"]
        val targetMessageLink = command.strings["target_message_link"]
        if (sourceMessageLink.isNullOrBlank() || targetMessageLink.isNullOrBlank()) {
            response.respond {
                content = "Invalid command usage. Please provide both `source_message_link` and `target_message_link`."
            }
            return
        }

        val sourceMessage = roomsDiscordClient.getMessageFromUrl(sourceMessageLink)
        if (sourceMessage == null) {
            response.respond {
                content = "Invalid source message link or message could not be fetched."
            }
            return
        }

        val targetMessage = roomsDiscordClient.getMessageFromUrl(targetMessageLink)
        if (targetMessage == null) {
            response.respond {
                content = "Invalid target message link or message could not be fetched."
            }
            return
        }

        roomsDiscordClient.editMessage(
            channelId = targetMessage.channelId,
            messageId = targetMessage.messageId,
            newText = sourceMessage.text,
        )
        response.respond {
            content = "Edited target message in <#${targetMessage.channelId}> using source message content."
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
}
