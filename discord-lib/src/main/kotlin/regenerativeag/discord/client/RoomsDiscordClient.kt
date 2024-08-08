package regenerativeag.discord.client

import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.DiscordMessage
import dev.kord.common.entity.Snowflake
import dev.kord.rest.builder.message.AllowedMentionsBuilder
import dev.kord.rest.json.request.ListThreadsByTimestampRequest
import dev.kord.rest.request.KtorRequestException
import dev.kord.rest.route.Position
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import regenerativeag.discord.Discord
import regenerativeag.discord.getLocalDate
import regenerativeag.discord.getUserId
import regenerativeag.discord.model.ChannelId
import regenerativeag.discord.model.Message
import regenerativeag.discord.model.UserId
import java.time.LocalDate

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

    fun readMessagesFromChannelAndSubChannels(
        readBackUntil: LocalDate,
        channelId: ChannelId,
    ): List<Message> {
        val messages = mutableListOf<Message>()
        val subChannels = mutableSetOf<ChannelId>()

        runBlocking {
            val (messagesInChannel, threadsInChannel) = readMessagesFromChannel(readBackUntil, channelId)
            messages.addAll(messagesInChannel)
            subChannels.addAll(threadsInChannel)

            val archivedThreadsInChannel = listArchivedThreads(channelId)
            subChannels.addAll(archivedThreadsInChannel)

            subChannels.forEach { threadId ->
                val (messagesInThread, threadsInThread) = readMessagesFromChannel(readBackUntil, threadId)
                if (threadsInThread.isNotEmpty()) {
                    throw RuntimeException("found ${threadsInThread.size} sub-threads in ${channelNameCache.lookup(threadId)} of ${channelNameCache.lookup(channelId)}")
                }
                messages.addAll(messagesInThread)
            }
        }

        return messages
    }

    /** Returns the messages read as well as the sub-channels (threads) discovered while reading messages */
    private suspend fun readMessagesFromChannel(
        readBackUntil: LocalDate,
        channelId: ChannelId,
        pageSize: Int = 100
    ): Pair<List<Message>, MutableSet<ChannelId>> {
        val channelName = channelNameCache.lookup(channelId)
        logger.debug { "Getting messages from '$channelName'" }
        var lastSeenDiscordMessage: DiscordMessage? = null
        val threadsInChannel = mutableSetOf<ChannelId>()
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
                        threadsInChannel.add(subChannel.id.value)
                    }
                    messagesInChannel.add(Message(it.getUserId(), it.getLocalDate()))
                }
            lastSeenDiscordMessage = discordMessages.lastOrNull()
        } while (messages.size == pageSize && lastSeenDiscordMessage!!.getLocalDate() >= readBackUntil)
        return messagesInChannel to threadsInChannel
    }

    private suspend fun listArchivedThreads(channelId: ChannelId, limit: Int = 100): List<ChannelId> {
        return try {
            val archivedChannels = restClient.channel.listPublicArchivedThreads(
                Snowflake(channelId),
                ListThreadsByTimestampRequest(limit=limit)
            ).threads
            archivedChannels.onEach { channelNameCache.cacheFrom(it) }
            logger.debug { "found archived threads in channel: ${archivedChannels.map { it.name.value }}" }
            archivedChannels.map { it.id.value }
        } catch (e: KtorRequestException) {
            if (e.status.code == 403) {
                val channelName = channelNameCache.lookup(channelId)
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