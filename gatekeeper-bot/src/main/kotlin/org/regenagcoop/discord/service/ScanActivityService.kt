package org.regenagcoop.discord.service

import kotlinx.coroutines.*
import mu.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActivityHistory
import java.time.LocalDate

class ScanActivityService(
    discord: Discord,
    private val persistedActivityService: PersistedActivityService,
    ) : DiscordClient(discord) {
        private val logger = KotlinLogging.logger { }

    /**
     * Load activity history by reading the persistence channel and scanning any missing data from channels & threads
     */
    suspend fun scanForCompleteActivityHistory(today: LocalDate, persistedHistoryMessages: List<Message>): Pair<ActivityHistory, Set<LocalDate>> {
        val persistedActivityHistory = persistedActivityService.computePersistedActivityHistory(persistedHistoryMessages)
        val usersWhoPostedAndReactedByDate = persistedActivityHistory.usersWhoPostedAndReactedByDate

        val earliestUnpersistedDate = persistedActivityService.computeEarliestUnpersistedDate(
            today,
            usersWhoPostedAndReactedByDate.keys
        )

        val scannedMessages = scanMessagesFromAllChannelsAndThreads(earliestUnpersistedDate)

        val activityHistory = combinePersistedAndScannedHistory(persistedActivityHistory, scannedMessages)
        return activityHistory to usersWhoPostedAndReactedByDate.keys
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
        persistedActivityHistory: PersistedActivityHistory,
        scannedMessages: List<Message>,
    ): ActivityHistory {

        val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
        val reactionHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()

        // add scanned data into history
        scannedMessages.forEach { message ->
            addTo(postHistory, message.userId, message.utcDate)
        }

        // add persisted data (excluding role changes) into history
        persistedActivityHistory.usersWhoPostedAndReactedByDate.forEach { date, (usersWhoPosted, usersWhoReacted) ->
            usersWhoPosted.forEach { userId ->
                addTo(postHistory, userId, date)
            }
            usersWhoReacted.forEach { userId ->
                addTo(reactionHistory, userId, date)
            }
        }

        // return complete activity history at the present moment
        return ActivityHistory(postHistory, reactionHistory, persistedActivityHistory.roleChangeHistory)
    }

    private fun addTo(history: MutableMap<UserId, MutableSet<LocalDate>>, userId: UserId, date: LocalDate) {
        if (userId !in history) {
            history[userId] = mutableSetOf(date)
        } else {
            history[userId]!!.add(date)
        }
    }
}