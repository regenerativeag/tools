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
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class DiscordMocker(
    private val restClient: RestClient = mockk<RestClient>()
) {

    val mock: Discord

    private val capturedMessages = mutableListOf<CapturedMessage>()
    private val messagesInChannel = mutableMapOf<ChannelId, List<Message>>()

    init {
        mock = object : Discord(mockk<HttpClient>(), 111uL,"test_token", false, restClient) {
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
            val transformedCapturedMessage = transformCapturedMessageWithMatchTokens(expectedMessage, capturedMessage)
            assertEquals(expectedMessage, transformedCapturedMessage)
        }
    }

    private fun transformCapturedMessageWithMatchTokens(
        expectedMessage: CapturedMessage,
        capturedMessage: CapturedMessage
    ): CapturedMessage {
        var transformedCapturedMessage = capturedMessage

        val startTimestampIdx = expectedMessage.text.indexOf("MATCH_NOW_TIMESTAMP")
        if (startTimestampIdx != -1) {
            val cText = capturedMessage.text
            val endTimestampIdx = cText.indexOf('Z', startIndex=startTimestampIdx)
            val nowTimestampStr = cText.substring(startTimestampIdx, endTimestampIdx+1)
            val timestamp = try {
                Instant.parse(nowTimestampStr)
            } catch (e: Throwable) {
                fail("Timestamp ($nowTimestampStr) of expectedMessage=$expectedMessage, capturedMessage=$capturedMessage could not be converted to a timestamp.")
            }
            val now = Instant.now()
            // assert: the actual timestamp is close to now
            assertTrue(timestamp.isBefore(now.plusSeconds(3)) && timestamp.isAfter(now.minusSeconds(3)), "timestamp=$timestamp expected to be within seconds of now=$now")
            val newText = cText.substring(0, startTimestampIdx) +
                    "MATCH_NOW_TIMESTAMP" +
                    cText.substring(endTimestampIdx+1)
            transformedCapturedMessage = transformedCapturedMessage.copy(text=newText)
        }

        return transformedCapturedMessage
    }

    fun mockMessagesInChannel(channelId: ChannelId, messages: List<String>) {
        messagesInChannel[channelId] = messages.map {
            val messageId = Random.Default.nextULong()
            Message(channelId, messageId, UserIds.gatekeeperBot, Clock.System.now(), it)
        }
    }
}