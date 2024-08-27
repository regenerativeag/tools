package org.regenagcoop.discord.client

import co.touchlab.stately.collections.ConcurrentMutableList
import dev.kord.common.entity.DiscordMessage
import dev.kord.common.entity.Snowflake
import dev.kord.rest.builder.message.AllowedMentionsBuilder
import dev.kord.rest.json.request.ListThreadsByTimestampRequest
import dev.kord.rest.request.KtorRequestException
import dev.kord.rest.route.Position
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.regenagcoop.coroutine.parallelMapIO
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.getLocalDate
import org.regenagcoop.discord.getUserId
import org.regenagcoop.discord.model.ChannelId
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import java.time.LocalDate

open class RoomsDiscordClient(discord: Discord) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    open suspend fun postMessage(
        message: String,
        channelId: ChannelId,
        usersMentioned: List<UserId> = listOf()
    ) {
        if (dryRun) {
            val channelName = channelNameCache.lookup(channelId)
            logger.info { "Dry run... would have posted: \"$message\" in $channelName."}
        } else {
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

    suspend fun readMessagesFromChannelAndSubChannels(
        readBackUntil: LocalDate,
        channelId: ChannelId,
    ): List<Message> {

        return coroutineScope {
            val threadMessagesDeferreds = ConcurrentMutableList<Deferred<List<Message>>>()

            // Start fetching messages from the top level channel
            val channelMessagesDeferred = async {
                fetchMessagesFromChannel(readBackUntil, channelId) { threadChannelId ->
                    // any time we encounter a thread while reading messages in the channel,
                    // add a deferred for the thread's messages to threadMessagesDeffereds
                    threadMessagesDeferreds.add(async {
                        fetchMessagesFromChannel(readBackUntil, threadChannelId) { subThreadChannelId ->
                            // Throw if we unexpectedly find a sub-thread of a thread
                            val channelName = channelNameCache.lookup(channelId)
                            val threadName = channelNameCache.lookup(threadChannelId)
                            val subThreadName = channelNameCache.lookup(subThreadChannelId)

                            throw IllegalStateException("found sub-thread '$subThreadName' in thread '$threadName' of $channelName")
                        }
                    })
                }
            }

            // For every archived thread in the channel...
            val archivedThreadsInChannel = listArchivedThreads(channelId)
            archivedThreadsInChannel.forEach { archivedThreadChannelId ->
                // add a deferred for the archived thread's messages to threadMessagesDeferreds
                threadMessagesDeferreds.add(async {
                    fetchMessagesFromChannel(readBackUntil, archivedThreadChannelId) { subThreadChannelId ->
                        // throw if we unexpectedly find a sub-thread of an archived thread
                        val channelName = channelNameCache.lookup(channelId)
                        val threadName = channelNameCache.lookup(archivedThreadChannelId)
                        val subThreadName = channelNameCache.lookup(subThreadChannelId)

                        throw IllegalStateException("found sub-thread '$subThreadName' in thread '$threadName' of $channelName")
                    }
                })
            }

            // wait until we've read all messages from the channel
            val channelMessages = channelMessagesDeferred.await()

            // once the channel has be read, we know all threads in the channel have been discovered
            // wait until all threads have been read
            val messagesPerSubThread = threadMessagesDeferreds.awaitAll()

            // the result is the messages in the channel plus the messages in all of its threads
            channelMessages + messagesPerSubThread.flatten()
        }
    }


    private suspend fun fetchMessagesFromChannel(
        readBackUntil: LocalDate,
        channelId: ChannelId,
        pageSize: Int = 100,
        onThreadFound: suspend (ChannelId) -> Unit
    ): List<Message> {
        val channelName = channelNameCache.lookup(channelId)
        logger.debug { "Getting messages from '$channelName'" }
        var lastSeenDiscordMessage: DiscordMessage? = null
        val messagesInChannel = mutableListOf<Message>()

        do {
            val discordMessages = getMessages(channelId, pageSize, lastSeenDiscordMessage)
            val messages = discordMessages
                .filter {
                    // TODO: Important ⚠⚠⚠⚠
                    // Could be missing some posts: if (a) a thread isn't archived and (b) the thread's creation timestamp was before the earliest date and (c) the thread has recent activity... then this fetch would miss activity in that thread.
                    it.getLocalDate() >= readBackUntil
                }
                .onEach {
                    val subChannel = (it.thread.value)
                    if (subChannel != null) {
                        // if this message is the start of a thread
                        channelNameCache.cacheFrom(subChannel)
                        onThreadFound(subChannel.id.value)
                    }
                    messagesInChannel.add(Message(it.getUserId(), it.getLocalDate()))
                }
            lastSeenDiscordMessage = discordMessages.lastOrNull()
        } while (messages.size == pageSize && lastSeenDiscordMessage!!.getLocalDate() >= readBackUntil)

        return messagesInChannel
    }

    private suspend fun listArchivedThreads(channelId: ChannelId, limit: Int = 100): List<ChannelId> {
        val channelName = channelNameCache.lookup(channelId)
        return try {
            val archivedChannels = restClient.channel.listPublicArchivedThreads(
                Snowflake(channelId),
                ListThreadsByTimestampRequest(limit=limit)
            ).threads
            archivedChannels.onEach { channelNameCache.cacheFrom(it) }
            logger.debug { "found archived threads in $channelName: ${archivedChannels.map { it.name.value }}" }
            archivedChannels.map { it.id.value }
        } catch (e: KtorRequestException) {
            if (e.status.code == 403) {
                logger.debug { "Access denied for channel '$channelName'" }
                return listOf()
            } else {
                throw e
            }
        }
    }

    private suspend fun getMessages(channelId: ChannelId, pageSize: Int = 100, before: DiscordMessage? = null): List<DiscordMessage> {
        val channelName = channelNameCache.lookup(channelId)
        return try {
            restClient.channel.getMessages(
                Snowflake(channelId),
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