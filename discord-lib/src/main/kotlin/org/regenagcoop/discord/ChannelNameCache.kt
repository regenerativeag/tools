package org.regenagcoop.discord

import dev.kord.common.entity.DiscordChannel
import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.RestClient
import org.regenagcoop.discord.model.ChannelId
import java.util.concurrent.ConcurrentHashMap

class ChannelNameCache(
    private val restClient: RestClient,
) {
    private val cache = ConcurrentHashMap<ChannelId, String>()

    suspend fun lookup(channelId: ChannelId): String {
        if (!cache.containsKey(channelId)) {
            val channelName = restClient.channel.getChannel(Snowflake(channelId)).getChannelName()
            cache[channelId] = channelName
        }
        return cache[channelId]!!
    }

    fun cacheFrom(channel: DiscordChannel) {
        val channelId = channel.id.value
        if (!cache.containsKey(channelId)) {
            cache[channelId] = channel.getChannelName()
        }
    }

    private fun DiscordChannel.getChannelName() = this.name.value ?: "UNKNOWN"
}