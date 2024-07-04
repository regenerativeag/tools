package regenerativeag.discord

import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.DiscordChannel
import dev.kord.common.entity.DiscordMessage
import dev.kord.common.entity.Snowflake
import dev.kord.rest.json.request.ListThreadsByTimestampRequest
import dev.kord.rest.request.KtorRequestException
import dev.kord.rest.route.Position
import dev.kord.rest.service.RestClient
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import regenerativeag.model.MutablePostHistory
import regenerativeag.model.PostHistory
import regenerativeag.model.UserId
import java.time.LocalDate

class PostHistoryFetcher(
    private val restClient: RestClient,
    private val guildId: ULong,
    /* how many days to fetch for. days=1 means just fetch today's messages */
    private val days: Int,
    private val today: LocalDate,
    private val channelNameCache: ChannelNameCache
) {
    private val logger = KotlinLogging.logger { }

    fun fetch(): PostHistory {
        val sGuildId = Snowflake(guildId)
        val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
        val earliestValidDate = today.minusDays(days - 1L)
        runBlocking {
            val channels = restClient.guild.getGuildChannels(sGuildId).filter { it.type != ChannelType.GuildCategory }
            logger.debug { "Found channels: ${channels.map {it.name.value}}" }
            channels.forEach { channel ->
                val (channelPostHistory, threadsInChannel) = readChannelPostHistory(earliestValidDate, channel)
                postHistory.addHistoryFrom(channelPostHistory)
                val archivedThreads = listArchivedThreads(channel)
                threadsInChannel.addAll(archivedThreads)
                threadsInChannel.forEach { thread ->
                    val (threadPostHistory, threadsInThread) = readChannelPostHistory(earliestValidDate, thread)
                    if (threadsInThread.size != 0) {
                        throw RuntimeException("found ${threadsInThread.size} threads in ${thread.name.value} of ${channel.name.value}")
                    }
                    postHistory.addHistoryFrom(threadPostHistory)
                }
            }
        }
        return postHistory
    }

    private suspend fun readChannelPostHistory(
        earliestValidDate: LocalDate,
        channel: DiscordChannel,
        limit: Int = 100
    ): Pair<MutablePostHistory, MutableSet<DiscordChannel>> {
        logger.debug { "Getting messages from '${channel.name.value}'" }
        channelNameCache.cacheFrom(channel)
        var lastSeenMessage: DiscordMessage? = null
        val threadsInChannel = mutableSetOf<DiscordChannel>()
        val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
        do {
            val messages = try {
                restClient.channel.getMessages(
                    channel.id,
                    limit = limit,
                    position = lastSeenMessage?.let { Position.Before(it.id) }
                )
            } catch (e: KtorRequestException) {
                if (e.status.code == 403) {
                    logger.debug { "Access denied for channel '${channel.name.value}'" }
                    return mutableMapOf<UserId, MutableSet<LocalDate>>() to mutableSetOf()
                } else {
                    throw e
                }
            }
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
            // TODO: possible bug: this may not include all threads.. eg if thread isn't archived, but is before the earliestDate
            messages.forEach { message ->
                val thread = message.thread.value
                if (thread != null) {
                    threadsInChannel.add(thread)
                }
            }
            lastSeenMessage = messages.lastOrNull()
        } while (messages.size == limit && lastSeenMessage!!.getLocalDate() >= earliestValidDate)
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

    private fun MutablePostHistory.addHistoryFrom(other: MutablePostHistory) {
        other.forEach { (userId, dates) ->
            if (userId !in this) {
                this[userId] = dates
            } else {
                this[userId]!!.addAll(dates)
            }
        }
    }
}