package org.regenagcoop.discord.model

data class SlashCommandInteraction(
    val commandName: String,
    val userId: UserId,
    val channelId: Long,
    val stringOptions: Map<String, String>,
    val channelOptions: Map<String, Long>,
    val isAdmin: Boolean,
    val respond: suspend (String, isError: Boolean) -> Unit,
)
