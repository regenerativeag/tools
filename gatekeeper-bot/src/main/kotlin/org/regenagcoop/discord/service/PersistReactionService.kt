package org.regenagcoop.discord.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.ChannelId
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.MessageId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import java.time.LocalDate

class PersistReactionService(
    private val discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
    today: LocalDate,
    persistedMessages: List<Message>,
) {
    private val logger = KotlinLogging.logger { }

    private val mutex = Mutex()
    private var yesterdayMessageLocation: MessageLocation? = messageLocationFor(today.minusDays(1), persistedMessages)
    private var todayMessageLocation: MessageLocation? = messageLocationFor(today, persistedMessages)

    suspend fun persistReaction(date: LocalDate, userId: UserId) {
        mutex.withLock {
            if (todayMessageLocation == null) {
                throw NotImplementedError("TODO create today's message with userId")
            } else if (date == todayMessageLocation!!.forDate) {
                throw NotImplementedError("TODO add to today's message")
            } else if (date.plusDays(1) == todayMessageLocation!!.forDate) {
                throw NotImplementedError("TODO add to yesterday's message, or create it if it doesn't exist")
            } else if (date.minusDays(1) == todayMessageLocation!!.forDate) {
                // TODO move todayMessageLocation to yesterdayMessageLocation
                throw NotImplementedError("TODO create today's message with userId")
            }
        }
    }

    private companion object {
        data class MessageLocation(val forDate: LocalDate, val channelId: ChannelId, val messageId: MessageId)

        fun messageLocationFor(date: LocalDate, persistedHistoryMessages: List<Message>): MessageLocation? {
            val prefix = "Users who reacted on $date:"
            val message = persistedHistoryMessages.singleOrNull { it.text.startsWith(prefix) }
            return message?.let {
                MessageLocation(date, it.channelId, it.messageId)
            }
        }
    }
}