package org.regenagcoop.discord.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import java.time.LocalDate

class PersistReactionService(
    private val discord: Discord,
    activeMemberConfig: ActiveMemberConfig,
    private var today: LocalDate,
    persistedMessages: List<Message>,
) {
    private val logger = KotlinLogging.logger { }
    private val channelId = activeMemberConfig.persistenceConfig.channel
    private val mutex = Mutex()
    private var yesterdayMessage: Message? = findMessageFor(today.minusDays(1), persistedMessages)
    private var todayMessage: Message? = findMessageFor(today, persistedMessages)

    suspend fun persistReaction(date: LocalDate, userId: UserId) {
        mutex.withLock {
            val yesterday = today.minusDays(1)
            val tomorrow = today.plusDays(1)
            when (date) {
                today -> {
                    todayMessage = postOrEditReactionHistoryMessage(date, userId, todayMessage)
                }
                yesterday -> {
                    yesterdayMessage = postOrEditReactionHistoryMessage(date, userId, yesterdayMessage)
                }
                tomorrow -> {
                    logger.debug { "Received reaction for tomorrow. Switching today ($today) to yesterday ($yesterday) and creating a new today ($tomorrow)." }
                    yesterdayMessage = todayMessage
                    today = tomorrow
                    todayMessage = postNewReactionHistoryMessage(date, userId)
                }
                else -> {
                    if (date > tomorrow) {
                        logger.warn { "Received reaction for $date. Did no users react yesterday? ... Making today=$date" }
                        today = date
                        todayMessage = postNewReactionHistoryMessage(date, userId)
                        yesterdayMessage = null
                    } else {
                        throw IllegalArgumentException("Received reaction for userId=$userId, however reactionDate=$date was before yesterday ($yesterday).")
                    }
                }
            }
        }
    }

    private suspend fun postOrEditReactionHistoryMessage(
        date: LocalDate,
        userId: UserId,
        existingMessageInfo: Message?
    ) = if (existingMessageInfo == null) {
        postNewReactionHistoryMessage(date, userId)
    } else {
        editReactionHistoryMessage(existingMessageInfo, date, userId)
    }

    private suspend fun postNewReactionHistoryMessage(date: LocalDate, userId: UserId): Message {
        logger.debug { "Creating new reaction history message for $date, starting with userId=$userId" }
        return discord.rooms.postMessage(createMessage(date, userId), channelId)
    }

    private suspend fun editReactionHistoryMessage(messageToEdit: Message, date: LocalDate, userId: UserId): Message {
        logger.debug { "Adding userId=$userId to $date's reaction history message" }
        val newText = "${messageToEdit.text}, $userId"
        return discord.rooms.editMessage(channelId, messageToEdit.messageId, newText)
    }

    private fun findMessageFor(date: LocalDate, persistedHistoryMessages: List<Message>): Message? {
        val prefix = "Users who reacted on $date:"
        return persistedHistoryMessages.singleOrNull { it.text.startsWith(prefix) }
    }

    private fun createMessage(date: LocalDate, firstUser: UserId): String {
        return "Users who reacted on $date: $firstUser"
    }
}