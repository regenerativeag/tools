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

    val commandDefinitions = listOf(
        SlashCommandDefinition(
            name = postCommandName,
            description = "Post a message to a room as our bot",
            options = listOf(
                SlashCommandOption("message_to_copy", "A link to a message you want to copy", SlashCommandOptionType.STRING, true),
                SlashCommandOption("channel", "The channel you want our bot to post to", SlashCommandOptionType.CHANNEL, true)
            )
        ),
        SlashCommandDefinition(
            name = editCommandName,
            description = "Edit one of the bot's messages",
            options = listOf(
                SlashCommandOption("message_to_edit", "A link to a message written by this bot that you want to edit", SlashCommandOptionType.STRING, true),
                SlashCommandOption("message_to_copy", "A link to a message containing exactly what you want the bot's message to say", SlashCommandOptionType.STRING, true),
            )
        )
    )

    suspend fun handleSlashCommand(interaction: SlashCommandInteraction) {
        when (interaction.commandName) {
            postCommandName -> handlePostCommand(interaction)
            editCommandName -> handleEditCommand(interaction)
            else -> {
              throw IllegalArgumentException("Unrecognized slash command: ${interaction.commandName}")
            }
        }
    }

    private suspend fun handlePostCommand(interaction: SlashCommandInteraction) {
        try {
            if (!interaction.isAdmin) {
                interaction.respond("Only admins can run this command.", true)
                return
            }

            val urlOfMessageToCopy = interaction.stringOptions["message_to_copy"]
            val channelId = interaction.channelOptions["channel"]
            if (urlOfMessageToCopy.isNullOrBlank() || channelId == null) {
                interaction.respond("Invalid command usage. Please provide both `message_to_copy` and `channel`.", true)
                return
            }

            val messageToCopy = roomsDiscordClient.getMessageFromUrl(urlOfMessageToCopy)

            roomsDiscordClient.postMessage(message = messageToCopy.text, channelId = channelId)
            interaction.respond("Posted message to <#$channelId>.", false)
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Failed to post message due to an unexpected error."
            interaction.respond(errorMessage, true)
        }
    }

    private suspend fun handleEditCommand(interaction: SlashCommandInteraction) {
        try {
            if (!interaction.isAdmin) {
                interaction.respond("Only admins can run this command.", true)
                return
            }

            val urlOfMessageToCopy = interaction.stringOptions["message_to_copy"]
            val urlOfMessageToEdit = interaction.stringOptions["message_to_edit"]
            if (urlOfMessageToCopy.isNullOrBlank() || urlOfMessageToEdit.isNullOrBlank()) {
                interaction.respond("Invalid command usage. Please provide both `message_to_copy` and `message_to_edit`.", true)
                return
            }

            val messageToCopy = roomsDiscordClient.getMessageFromUrl(urlOfMessageToCopy)
            val messageToEdit = roomsDiscordClient.getMessageFromUrl(urlOfMessageToEdit)

            roomsDiscordClient.editMessage(
                channelId = messageToEdit.channelId,
                messageId = messageToEdit.messageId,
                newText = messageToCopy.text,
            )
            interaction.respond("Edited message in <#${messageToEdit.channelId}>: ${urlOfMessageToEdit}.", false)
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Failed to edit message due to an unexpected error."
            interaction.respond(errorMessage, true)
        }
    }

    private fun normalizeSlashCommandName(configValue: String): String {
        val normalized = configValue.trim().removePrefix("/").lowercase()
        require(normalized.isNotBlank()) { "Slash command name cannot be blank. Config value='$configValue'" }
        return normalized
    }
}
