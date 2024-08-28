package org.regenagcoop.discord.client

import co.touchlab.stately.collections.ConcurrentMutableList
import dev.kord.common.entity.DiscordMessage
import dev.kord.common.entity.Snowflake
import dev.kord.rest.builder.message.AllowedMentionsBuilder
import dev.kord.rest.json.request.ListThreadsByTimestampRequest
import dev.kord.rest.json.response.ListThreadsResponse
import dev.kord.rest.request.KtorRequestException
import dev.kord.rest.route.Position
import kotlinx.coroutines.*
import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.regenagcoop.coroutine.parallelMapIO
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.getUtcDate
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

    suspend fun readMessagesFromActiveThreadsInGuild(readBackUntil: LocalDate): List<Message> {
        val activeThreadIds = discord.guild.getActiveThreadIds()
        val activeThreadNames = activeThreadIds.parallelMapIO { discord.channelNameCache.lookup(it) }
        logger.debug { "Found active threads: $activeThreadNames" }

        return activeThreadIds.zip(activeThreadNames).parallelMapIO { (activeThreadId, activeThreadName) ->
            try {
                discord.rooms.fetchMessagesFromChannel(readBackUntil, activeThreadId)
            } catch(e: Throwable) {
                if (e !is CancellationException) {
                    logger.debug(e) { "Failed to read messages from active thread: $activeThreadName"}
                }
                throw e
            }
        }.flatten()
    }

    suspend fun readMessagesFromTopLevelChannelsInGuild(readBackUntil: LocalDate): List<Message> {
        val topLevelChannelIds = discord.guild.getTopLevelChannelIds()
        val topLevelChannelNames = topLevelChannelIds.parallelMapIO { discord.channelNameCache.lookup(it) }
        logger.debug { "Found top-level channels: $topLevelChannelNames" }

        return topLevelChannelIds.zip(topLevelChannelNames).parallelMapIO { (topLevelChannelId, topLevelChannelName) ->
            try {
                val hasAccess = botHasAccessToChannel(topLevelChannelId)
                if (!hasAccess) {
                    logger.debug { "Access denied to channel: $topLevelChannelName"}
                    listOf()
                } else {
                    coroutineScope {
                        // Start fetching messages from this top-level channel
                        val channelMessagesDeferred = async {
                            fetchMessagesFromChannel(readBackUntil, topLevelChannelId)
                        }

                        // Start fetching messages from archived threads in this channel
                        val messagesFromArchivedThreadsDeferred = async {
                            // fetch relevant messages from relevant archived threads in this channel
                            fetchArchivedMessagesFromChannel(readBackUntil, topLevelChannelId)
                        }

                        channelMessagesDeferred.await() + messagesFromArchivedThreadsDeferred.await()
                    }
                }
            } catch(e: Throwable) {
                if (e !is CancellationException) {
                    logger.debug(e) { "Failed to read messages from top-level channel: $topLevelChannelName" }
                }
                throw e
            }
        }.flatten()
    }

    private suspend fun botHasAccessToChannel(channelId: ChannelId): Boolean = try {
        restClient.channel.getMessages(Snowflake(channelId), limit = 1)
        true
    } catch(e: KtorRequestException) {
        if (e.status.code == 403) {
            false
        } else {
            val channelName = channelNameCache.lookup(channelId)
            logger.debug(e) { "Failed to determine whether bot has access to channel $channelName"}
            throw e
        }
    }


    private suspend fun fetchMessagesFromChannel(
        readBackUntil: LocalDate,
        channelId: ChannelId,
        pageSize: Int = 100,
    ): List<Message> {
        val channelName = channelNameCache.lookup(channelId)
        logger.debug { "Getting messages from '$channelName'" }
        var lastSeenDiscordMessage: DiscordMessage? = null
        val messagesInChannel = mutableListOf<Message>()

        do {
            // fetch messages from the channel, one page at a time going backwards in history
            val discordMessages = getMessages(channelId, pageSize, lastSeenDiscordMessage)
            val messages = discordMessages
                .filter {
                    it.getUtcDate() >= readBackUntil
                }
                .onEach {
                    messagesInChannel.add(Message(it.getUserId(), it.timestamp))
                }
            lastSeenDiscordMessage = discordMessages.lastOrNull()
        } while (messages.size == pageSize && lastSeenDiscordMessage!!.getUtcDate() >= readBackUntil)

        return messagesInChannel
    }

    private suspend fun fetchArchivedMessagesFromChannel(
        readBackUntil: LocalDate,
        channelId: ChannelId,
        pageSize: Int = 5,
    ): List<Message> {
        val channelName = channelNameCache.lookup(channelId)
        val threadNameAndMessagesPerThread = ConcurrentMutableList<Pair<String, List<Message>>>()

        suspend fun addMessagesFrom(
            listThreadsFunction: suspend (Snowflake, ListThreadsByTimestampRequest) -> ListThreadsResponse
        ) {
            var lastArchivedTimestamp: Instant? = null
            do {
                val archivedThreads = try {
                    listThreadsFunction(
                        Snowflake(channelId),
                        ListThreadsByTimestampRequest(before = lastArchivedTimestamp, limit = pageSize)
                    ).threads
                } catch(e: KtorRequestException) {
                    val message = e.message
                    if (e.status.code == 403) {
                        logger.debug { "Access denied when listing archived threads in '$channelName'. Please ensure the bot has the 'Manage Threads' permission enabled." }
                        throw e
                    } else if (e.status.code == 400 && message != null && message.contains("Cannot execute action on this channel type")) {
                        listOf() // ex: forum channels can't have archived messages listed.
                    } else {
                        logger.debug(e) { "Failed fetching archived threads in '$channelName'." }
                        throw e
                    }
                }

                if (archivedThreads.isEmpty()) {
                    break // no more threads to fetch
                }

                val messagesPerThread = archivedThreads.parallelMapIO { archivedThread ->
                    channelNameCache.cacheFrom(archivedThread)
                    val archivedChannelId = archivedThread.id.value
                    fetchMessagesFromChannel(readBackUntil, archivedChannelId)
                }

                threadNameAndMessagesPerThread.addAll(archivedThreads.zip(messagesPerThread).map { (thread, messages) ->
                    thread.name.value.orEmpty() to messages
                })

                // Grab the archived timestamp from the last thread
                lastArchivedTimestamp = archivedThreads.last().let { lastArchivedThread ->
                    lastArchivedThread.threadMetadata.value?.archiveTimestamp
                        ?: throw IllegalStateException("Expected all archived threads to have an archiveTimestamp. ${lastArchivedThread.name.value} (${lastArchivedThread.id.value}) was missing an archived timestamp")
                }

                // Keep fetching until there are no relevant messages returned
            } while (messagesPerThread.last().isNotEmpty())
        }

        // get messages from public and private archived threads in parallel
        coroutineScope {
            launch {
                addMessagesFrom(restClient.channel::listPublicArchivedThreads)
            }
            launch {
                addMessagesFrom(restClient.channel::listPrivateArchivedThreads)
            }
        }

        logger.debug { "finished processing archived threads in $channelName: ${threadNameAndMessagesPerThread.map { it.first }}" }

        return threadNameAndMessagesPerThread.flatMap { it.second }
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
            logger.debug(e) { "Failed fetching messages from $channelName. beforeMessageId=${before?.id?.value}" }
            throw e
        }
    }
}