package regenerativeag.discord

import dev.kord.common.entity.DiscordChannel
import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.RestClient
import kotlinx.coroutines.runBlocking
import regenerativeag.model.ChannelId

class ChannelNameCache(
    private val restClient: RestClient,
) {
    private val cache = mutableMapOf<ChannelId, String>()

    fun lookup(channelId: ChannelId): String {
        return synchronized(cache) {
            if (channelId !in cache) {
                val channelName = runBlocking {
                    restClient.channel.getChannel(Snowflake(channelId)).getChannelName()
                }
                cache[channelId] = channelName
            }
            cache[channelId]!!
        }
    }

    fun cacheFrom(channel: DiscordChannel) {
        synchronized(cache) {
            val userId = channel.id.value
            if (userId !in cache) {
                cache[userId] = channel.getChannelName()
            }
        }
    }

    private fun DiscordChannel.getChannelName() = this.name.value ?: "UNKNOWN"
}