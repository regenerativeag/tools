package org.regenagcoop.discord.mock

import dev.kord.rest.service.RestClient
import io.ktor.client.*
import io.mockk.mockk
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.regenagcoop.UserIds
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.RoomsDiscordClient
import org.regenagcoop.discord.model.ChannelId
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.guildId
import java.time.LocalDate
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

                override suspend fun postMessage(message: String, channelId: ChannelId, usersMentioned: List<UserId>) {
                    capturedMessages.add(CapturedMessage(message, channelId))
                }
            }
        }
    }

    fun assertMessagesPostedEquals(vararg expectedMessages: CapturedMessage) {
        assertEquals(expectedMessages.size, capturedMessages.size)
        expectedMessages.zip(capturedMessages).forEach { (expectedMessage, capturedMessage) ->
            assertEquals(expectedMessage, capturedMessage)
        }
    }

    fun assertMessagesPostedEquals(message: CapturedMessage?) {
        if (message == null) {
            assertMessagesPostedEquals()
        } else {
            assertMessagesPostedEquals(message)
        }
    }

    fun mockMessagesInChannel(channelId: ChannelId, messages: List<String>) {
        messagesInChannel[channelId] = messages.map {
            Message(UserIds.gatekeeperBot, Clock.System.now(), it)
        }
    }
}