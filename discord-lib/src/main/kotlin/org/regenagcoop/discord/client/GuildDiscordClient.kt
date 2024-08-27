package org.regenagcoop.discord.client

import dev.kord.common.entity.ChannelType
import mu.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.ChannelId

open class GuildDiscordClient(discord: Discord) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    suspend fun getChannels(): List<ChannelId> = restClient.guild.getGuildChannels(sGuildId)
        .filter { it.type != ChannelType.GuildCategory }
        .onEach { channelNameCache.cacheFrom(it) }
        .map { it.id.value }
}