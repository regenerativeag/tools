package org.regenagcoop.discord.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.regenagcoop.coroutine.parallelMapIO
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.MutablePostHistory
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

class FetchPostHistoryClient(
    discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
    ) : DiscordClient(discord) {
        private val logger = KotlinLogging.logger { }

    /** Query discord for enough recent post history that active member roles can be computed */
    suspend fun fetchPostHistory(today: LocalDate): PostHistory = withContext(Dispatchers.IO) {
        val daysToLookBack = activeMemberConfig.maxWindowSize
        val earliestValidDate = today.minusDays(daysToLookBack - 1L)

        val channelIds = discord.guild.getChannels()
        val channelNames = channelIds.parallelMapIO { discord.channelNameCache.lookup(it) }
        logger.debug { "Found channels: $channelNames" }
        val messagesPerChannel = channelIds.parallelMapIO { channelId ->
            discord.rooms.readMessagesFromChannelAndSubChannels(earliestValidDate, channelId)
        }

        val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
        messagesPerChannel.forEach { postHistory.addHistoryFrom(it) }
        postHistory
    }

    private fun MutablePostHistory.addHistoryFrom(messages: List<Message>) {
        messages.forEach { message ->
            if (message.userId !in this) {
                this[message.userId] = mutableSetOf(message.date)
            } else {
                this[message.userId]!!.add(message.date)
            }
        }
    }
}