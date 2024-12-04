package org.regenagcoop.discord.mock

import dev.kord.rest.service.RestClient
import io.ktor.client.*
import io.mockk.mockk
import kotlinx.datetime.Clock
import org.regenagcoop.UserIds
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.RoomsDiscordClient
import org.regenagcoop.discord.model.ChannelId
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.MessageId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.guildId
import java.time.LocalDate
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.assertEquals

class DiscordMocker(
    private val restClient: RestClient = mockk<RestClient>()
) {

    val mock: Discord

    private val capturedMessages = mutableListOf<CapturedMessage>()
    private val messagesInChannel = mutableMapOf<ChannelId, List<Message>>()

    init {
        mock = object : Discord(mockk<HttpClient>(), guildId,"test_token", false, restClient) {
            override val rooms = object : RoomsDiscordClient(this) {
                override suspend fun readMessagesFromChannel(
                    channelId: ChannelId,
                    readBackUntil: LocalDate?
                ): List<Message> {
                    return messagesInChannel[channelId] ?: listOf()
                }

                override suspend fun postMessage(message: String, channelId: ChannelId, usersMentioned: List<UserId>): Message {
                    capturedMessages.add(CapturedMessage(message, channelId))
                    return Message(channelId, Random.Default.nextULong(), UserIds.gatekeeperBot, Clock.System.now(), message)
                }

                override suspend fun editMessage(channelId: ChannelId, messageId: MessageId, newText: String): Message {
                    capturedMessages.add(CapturedMessage(newText, channelId, CapturedMessage.Type.EDIT))
                    return Message(channelId, messageId, UserIds.gatekeeperBot, Clock.System.now(), newText)
                }
            }
        }
    }

    fun assertCapturedMessagesEqual(vararg expectedMessages: CapturedMessage) {
        assertEquals(expectedMessages.size, capturedMessages.size)
        expectedMessages.zip(capturedMessages).forEach { (expectedMessage, capturedMessage) ->
            assertEquals(expectedMessage, capturedMessage)
        }
    }

    fun assertCapturedMessagesEqual(message: CapturedMessage?) {
        if (message == null) {
            assertCapturedMessagesEqual()
        } else {
            assertCapturedMessagesEqual(message)
        }
    }

    fun mockMessagesInChannel(channelId: ChannelId, messages: List<String>) {
        messagesInChannel[channelId] = messages.map {
            val messageId = Random.Default.nextULong()
            Message(channelId, messageId, UserIds.gatekeeperBot, Clock.System.now(), it)
        }
    }
}