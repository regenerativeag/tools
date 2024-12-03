package org.regenagcoop.discord.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.MessageId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import java.time.LocalDate

class PersistReactionService(
    private val discord: Discord,
    activeMemberConfig: ActiveMemberConfig,
    today: LocalDate,
    persistedMessages: List<Message>,
) {
    private val logger = KotlinLogging.logger { }
    private val channelId = activeMemberConfig.persistenceConfig.channel
    private val mutex = Mutex()
    private var yesterdayMessageInfo: MessageInfo? = findMessageInfoFor(today.minusDays(1), persistedMessages)
    private var todayMessageInfo: MessageInfo? = findMessageInfoFor(today, persistedMessages)

    suspend fun persistReaction(date: LocalDate, userId: UserId) {
        mutex.withLock {
            // TODO fix bug: should be able to add to yesterday's message even if today's message doesn't exist yet
            if (todayMessageInfo == null) {
                logger.debug { "Creating new reaction history message for today, $date, starting with userId=$userId" }
                todayMessageInfo = postNewReactionHistoryMessage(date, userId)
            } else if (date == todayMessageInfo!!.forDate) {
                logger.debug { "Adding userId=$userId to today's reaction history message ($date)" }
                todayMessageInfo = editReactionHistoryMessage(todayMessageInfo!!, date, userId)
            } else if (date.plusDays(1) == todayMessageInfo!!.forDate) {
                if (yesterdayMessageInfo == null) {
                    logger.debug { "Creating new reaction history message for yesterday, $date, starting with userId=$userId" }
                    yesterdayMessageInfo = postNewReactionHistoryMessage(date, userId)
                } else {
                    logger.debug { "Adding userId=$userId to yesterday's reaction history message ($date)" }
                    if (yesterdayMessageInfo!!.forDate != date) {
                        throw IllegalStateException("Yesterday's date should be $date, was ${yesterdayMessageInfo!!.forDate}")
                    }
                    yesterdayMessageInfo = editReactionHistoryMessage(yesterdayMessageInfo!!, date, userId)
                }
            } else if (date.minusDays(1) == todayMessageInfo!!.forDate) {
                logger.debug { "Received reaction for tomorrow. Switching today to yesterday. Creating new reaction history message for today, $date, starting with userId=$userId" }
                yesterdayMessageInfo = todayMessageInfo
                todayMessageInfo = postNewReactionHistoryMessage(date, userId)
            } else {
                throw IllegalArgumentException("Received reaction for date=$date that was neither today (${todayMessageInfo?.forDate}), yesterday (${yesterdayMessageInfo?.forDate}), nor tomorrow.")
            }
        }
    }

    private suspend fun postNewReactionHistoryMessage(date: LocalDate, userId: UserId): MessageInfo {
        val message = discord.rooms.postMessage(createMessage(date, userId), channelId)
        return MessageInfo(date, message.messageId, message.text)
    }

    private suspend fun editReactionHistoryMessage(messageToEdit: MessageInfo, date: LocalDate, userId: UserId): MessageInfo {
        val message = discord.rooms.editMessage(channelId, messageToEdit.messageId, "${messageToEdit.currentText}, $userId")
        return MessageInfo(date, message.messageId, message.text)
    }

    private fun findMessageInfoFor(date: LocalDate, persistedHistoryMessages: List<Message>): MessageInfo? {
        val prefix = "Users who reacted on $date:"
        val message = persistedHistoryMessages.singleOrNull { it.text.startsWith(prefix) }
        return message?.let {
            MessageInfo(date, it.messageId, it.text)
        }
    }

    private fun createMessage(date: LocalDate, firstUser: UserId): String {
        return "Users who reacted on $date: $firstUser"
    }

    private companion object {
        data class MessageInfo(val forDate: LocalDate, val messageId: MessageId, val currentText: String)
    }
}