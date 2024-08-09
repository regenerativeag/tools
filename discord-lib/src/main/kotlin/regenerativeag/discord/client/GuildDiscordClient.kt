package regenerativeag.discord.client

import dev.kord.common.entity.ChannelType
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import regenerativeag.discord.Discord
import regenerativeag.discord.model.ChannelId

open class GuildDiscordClient(discord: Discord) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    fun getChannels(): List<ChannelId> {
        return runBlocking {
            restClient.guild.getGuildChannels(sGuildId)
                .filter { it.type != ChannelType.GuildCategory }
                .onEach { channelNameCache.cacheFrom(it) }
                .map { it.id.value }
        }
    }
}