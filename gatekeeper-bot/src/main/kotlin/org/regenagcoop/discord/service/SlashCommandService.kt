package org.regenagcoop.discord.service

import mu.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.client.RoomsDiscordClient
import org.regenagcoop.discord.model.*
import org.regenagcoop.model.config.ActiveMemberConfig

/** Handles slash command registration and execution for moderator workflows. */
class SlashCommandService(
    discord: Discord,
    private val roomsDiscordClient: RoomsDiscordClient,
    private val activeMemberConfig: ActiveMemberConfig,
) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    private val postCommandName = normalizeSlashCommandName(activeMemberConfig.slashCommandConfig.postCommand)
    private val editCommandName = normalizeSlashCommandName(activeMemberConfig.slashCommandConfig.editCommand)

    fun getCommandDefinitions(): List<SlashCommandDefinition> = listOf(
        SlashCommandDefinition(
            name = postCommandName,
            description = "Post a copy of a linked message",
            options = listOf(
                SlashCommandOption("source_message_link", "Discord message link to copy content from", SlashCommandOptionType.STRING, true),
                SlashCommandOption("channel", "Channel where the message will be posted", SlashCommandOptionType.CHANNEL, true)
            )
        ),
        SlashCommandDefinition(
            name = editCommandName,
            description = "Edit a message to match another linked message",
            options = listOf(
                SlashCommandOption("source_message_link", "Discord message link to copy content from", SlashCommandOptionType.STRING, true),
                SlashCommandOption("target_message_link", "Discord message link to edit", SlashCommandOptionType.STRING, true)
            )
        )
    )

    suspend fun handleSlashCommand(interaction: SlashCommandInteraction) {
        when (interaction.commandName) {
            postCommandName -> handlePostCommand(interaction)
            editCommandName -> handleEditCommand(interaction)
            else -> {
                // no-op
            }
        }
    }

    private suspend fun handlePostCommand(interaction: SlashCommandInteraction) {
        if (!interaction.isAdmin) {
            interaction.respond("Only admins can run this command.", true)
            return
        }

        val sourceMessageLink = interaction.stringOptions["source_message_link"]
        val channelId = interaction.channelOptions["channel"]
        if (sourceMessageLink.isNullOrBlank() || channelId == null) {
            interaction.respond("Invalid command usage. Please provide both `source_message_link` and `channel`.", true)
            return
        }

        val sourceMessage = roomsDiscordClient.getMessageFromUrl(sourceMessageLink)
        if (sourceMessage == null) {
            interaction.respond("Invalid source message link or message could not be fetched.", true)
            return
        }

        roomsDiscordClient.postMessage(message = sourceMessage.text, channelId = channelId)
        interaction.respond("Posted message to <#$channelId> using content from the source message.", false)
    }

    private suspend fun handleEditCommand(interaction: SlashCommandInteraction) {
        if (!interaction.isAdmin) {
            interaction.respond("Only admins can run this command.", true)
            return
        }

        val sourceMessageLink = interaction.stringOptions["source_message_link"]
        val targetMessageLink = interaction.stringOptions["target_message_link"]
        if (sourceMessageLink.isNullOrBlank() || targetMessageLink.isNullOrBlank()) {
            interaction.respond("Invalid command usage. Please provide both `source_message_link` and `target_message_link`.", true)
            return
        }

        val sourceMessage = roomsDiscordClient.getMessageFromUrl(sourceMessageLink)
        if (sourceMessage == null) {
            interaction.respond("Invalid source message link or message could not be fetched.", true)
            return
        }

        val targetMessage = roomsDiscordClient.getMessageFromUrl(targetMessageLink)
        if (targetMessage == null) {
            interaction.respond("Invalid target message link or message could not be fetched.", true)
            return
        }

        roomsDiscordClient.editMessage(
            channelId = targetMessage.channelId,
            messageId = targetMessage.messageId,
            newText = sourceMessage.text,
        )
        interaction.respond("Edited target message in <#${targetMessage.channelId}> using source message content.", false)
    }

    private fun normalizeSlashCommandName(configValue: String): String {
        val normalized = configValue.trim().removePrefix("/").lowercase()
        require(normalized.isNotBlank()) { "Slash command name cannot be blank. Config value='$configValue'" }
        return normalized
    }
}
