package org.regenagcoop.discord.service

import kotlinx.coroutines.*
import mu.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.ActivityHistory
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

class FetchActivityService(
    discord: Discord,
    private val persistedActivityService: PersistedActivityService,
    ) : DiscordClient(discord) {
        private val logger = KotlinLogging.logger { }

    /**
     * Load activity history by reading the persistence channel and scanning any missing data from channels & threads
     */
    suspend fun fetchActivityHistory(today: LocalDate): Pair<ActivityHistory, Set<LocalDate>> {
        val persistedHistoryByDate = persistedActivityService.fetchPersistedHistoryByDate()

        val earliestUnpersistedDate = persistedActivityService.computeEarliestUnpersistedDate(
            today,
            persistedHistoryByDate.keys
        )

        val scannedMessages = scanMessagesFromAllChannelsAndThreads(earliestUnpersistedDate)

        val activityHistory = combinePersistedAndScannedHistory(persistedHistoryByDate, scannedMessages)
        return activityHistory to persistedHistoryByDate.keys
    }

    private suspend fun scanMessagesFromAllChannelsAndThreads(untilDate: LocalDate): List<Message> {
        logger.debug { "Scanning messages from all threads and channels until: $untilDate" }
        return coroutineScope {
            val messagesFromChannelsAndArchivedThreadsDeferred = async {
                discord.rooms.readMessagesFromTopLevelChannelsInGuild(untilDate)
            }

            val messagesFromActiveThreadsDeferred = async {
                discord.rooms.readMessagesFromActiveThreadsInGuild(untilDate)
            }

            messagesFromChannelsAndArchivedThreadsDeferred.await() + messagesFromActiveThreadsDeferred.await()
        }
    }

    private fun combinePersistedAndScannedHistory(
        persistedHistoryByDate: UsersWhoPostedAndReactedByDate,
        scannedMessages: List<Message>,
    ): ActivityHistory {

        val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
        val reactionHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()

        // add scanned data into history
        scannedMessages.forEach { message ->
            addTo(postHistory, message.userId, message.utcDate)
        }

        // add persisted data into history
        persistedHistoryByDate.forEach { date, (usersWhoPosted, usersWhoReacted) ->
            usersWhoPosted.forEach { userId ->
                addTo(postHistory, userId, date)
            }
            usersWhoReacted.forEach { userId ->
                addTo(reactionHistory, userId, date)
            }
        }

        return ActivityHistory(postHistory, reactionHistory)
    }

    private fun addTo(history: MutableMap<UserId, MutableSet<LocalDate>>, userId: UserId, date: LocalDate) {
        if (userId !in history) {
            history[userId] = mutableSetOf(date)
        } else {
            history[userId]!!.add(date)
        }
    }
}