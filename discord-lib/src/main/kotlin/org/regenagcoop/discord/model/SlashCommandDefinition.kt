package org.regenagcoop.discord.model

data class SlashCommandDefinition(
    val name: String,
    val description: String,
    val options: List<SlashCommandOption> = emptyList()
)

data class SlashCommandOption(
    val name: String,
    val description: String,
    val type: SlashCommandOptionType,
    val required: Boolean = false
)

enum class SlashCommandOptionType {
    STRING,
    CHANNEL
}
