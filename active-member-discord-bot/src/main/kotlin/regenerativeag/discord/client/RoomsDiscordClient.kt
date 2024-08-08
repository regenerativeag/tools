package regenerativeag.discord.client

import dev.kord.common.entity.Snowflake
import dev.kord.rest.builder.message.AllowedMentionsBuilder
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import regenerativeag.Discord
import regenerativeag.model.ChannelId
import regenerativeag.model.UserId

open class RoomsDiscordClient(discord: Discord) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    open fun postMessage(message: String, channelId: ChannelId, usersMentioned: List<UserId> = listOf()) {
        if (dryRun) {
            val channelName = channelNameCache.lookup(channelId)
            logger.info { "Dry run... would have posted: \"$message\" in $channelName."}
        } else {
            runBlocking {
                restClient.channel.createMessage((Snowflake(channelId))) {
                    this.content = message
                    this.allowedMentions = AllowedMentionsBuilder().also { builder ->
                        usersMentioned.forEach { userId ->
                            builder.users.add(Snowflake(userId))
                        }
                    }
                }
            }
        }
    }
}