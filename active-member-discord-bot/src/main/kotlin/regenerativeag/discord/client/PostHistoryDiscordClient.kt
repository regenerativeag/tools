package regenerativeag.discord.client

import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.DiscordChannel
import dev.kord.common.entity.DiscordMessage
import dev.kord.common.entity.Snowflake
import dev.kord.rest.json.request.ListThreadsByTimestampRequest
import dev.kord.rest.request.KtorRequestException
import dev.kord.rest.route.Position
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import regenerativeag.Discord
import regenerativeag.discord.getLocalDate
import regenerativeag.discord.getUserId
import regenerativeag.model.MutablePostHistory
import regenerativeag.model.PostHistory
import regenerativeag.model.UserId
import java.time.LocalDate

class PostHistoryDiscordClient(discord: Discord) : DiscordClient(discord){
    private val logger = KotlinLogging.logger { }

    /** Query dicord for enough recent post history that active member roles can be computed */
    fun fetch(today: LocalDate): PostHistory {
        val daysToLookBack = activeMemberConfig.maxWindowSize
        val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
        val earliestValidDate = today.minusDays(daysToLookBack - 1L)
        runBlocking {
            val channels = restClient.guild.getGuildChannels(sGuildId).filter { it.type != ChannelType.GuildCategory }
            logger.debug { "Found channels: ${channels.map {it.name.value}}" }
            channels.forEach { channel ->
                val channelPostHistory = readPostHistoryFromChannelAndSubChannels(earliestValidDate, channel)
                postHistory.addHistoryFrom(channelPostHistory)
            }
        }
        return postHistory
    }


    private suspend fun readPostHistoryFromChannelAndSubChannels(
        earliestValidDate: LocalDate,
        channel: DiscordChannel,
    ): MutablePostHistory {
        val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
        val subChannels = mutableSetOf<DiscordChannel>()

        val (channelPostHistory, threadsInChannel) = readPostHistoryFromChannel(earliestValidDate, channel)
        postHistory.addHistoryFrom(channelPostHistory)
        subChannels.addAll(threadsInChannel)

        val archivedThreadsInChannel = listArchivedThreads(channel)
        subChannels.addAll(archivedThreadsInChannel)

        subChannels.forEach { thread ->
            val (threadPostHistory, threadsInThread) = readPostHistoryFromChannel(earliestValidDate, thread)
            if (threadsInThread.isNotEmpty()) {
                throw RuntimeException("found ${threadsInThread.size} threads in ${thread.name.value} of ${channel.name.value}")
            }
            postHistory.addHistoryFrom(threadPostHistory)
        }

        return postHistory
    }

    private suspend fun readPostHistoryFromChannel(
        earliestValidDate: LocalDate,
        channel: DiscordChannel,
        pageSize: Int = 100
    ): Pair<MutablePostHistory, MutableSet<DiscordChannel>> {
        logger.debug { "Getting messages from '${channel.name.value}'" }
        channelNameCache.cacheFrom(channel)
        var lastSeenMessage: DiscordMessage? = null
        val threadsInChannel = mutableSetOf<DiscordChannel>()
        val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
        do {
            val messages = getMessages(channel.id, pageSize, lastSeenMessage)
            messages.filter {
                it.getLocalDate() >= earliestValidDate
            }.forEach { message ->
                val userId = message.getUserId()
                if (userId !in postHistory) {
                    postHistory[userId] = mutableSetOf()
                }
                postHistory[userId]!!.add(message.getLocalDate())
            }
            // TODO: Important ⚠⚠⚠⚠
            // Could be missing some posts: if (a) a thread isn't archived and (b) the thread's creation timestamp was before the earliest date and (c) the thread has recent activity... then this fetch would miss activity in that thread.
            messages.forEach { message ->
                val thread = message.thread.value
                if (thread != null) {
                    threadsInChannel.add(thread)
                }
            }
            lastSeenMessage = messages.lastOrNull()
        } while (messages.size == pageSize && lastSeenMessage!!.getLocalDate() >= earliestValidDate)
        return postHistory to threadsInChannel
    }

    private suspend fun listArchivedThreads(channel: DiscordChannel, limit: Int = 100): List<DiscordChannel> {
        return try {
            restClient.channel.listPublicArchivedThreads(
                channel.id,
                ListThreadsByTimestampRequest(limit=limit)
            ).threads.also { threads ->
                logger.debug { "found archived threads in channel: ${threads.map { it.name.value }}" }
            }
        } catch (e: KtorRequestException) {
            if (e.status.code == 403) {
                logger.debug { "Access denied for channel '${channel.name.value}'" }
                return listOf()
            } else {
                throw e
            }
        }
    }

    private fun MutablePostHistory.addHistoryFrom(other: PostHistory) {
        other.forEach { (userId, dates) ->
            if (userId !in this) {
                this[userId] = dates.toMutableSet()
            } else {
                this[userId]!!.addAll(dates)
            }
        }
    }

    private suspend fun getMessages(channelId: Snowflake, pageSize: Int = 100, before: DiscordMessage? = null): List<DiscordMessage> {
        val channelName = channelNameCache.lookup(channelId.value)
        return try {
            restClient.channel.getMessages(
                channelId,
                limit = pageSize,
                position = before?.let { Position.Before(before.id) }
            )
        } catch (e: KtorRequestException) {
            if (e.status.code == 403) {
                logger.debug { "Access denied for channel '$channelName'" }
                listOf()
            } else {
                throw e
            }
        }
    }
}