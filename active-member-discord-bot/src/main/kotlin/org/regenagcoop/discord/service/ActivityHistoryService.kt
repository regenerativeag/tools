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

class ActivityHistoryService(
    discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
    ) : DiscordClient(discord) {
        private val logger = KotlinLogging.logger { }

    /**
     * Load activity history by reading the persistence channel and scanning any missing data from channels & threads
     */
    suspend fun fetchActivityHistory(today: LocalDate): Pair<ActivityHistory, Set<LocalDate>> {
        val persistedHistoryByDate = fetchPersistedHistoryByDate()

        val earliestUnpersistedDate = computeEarliestUnpersistedDate(today, persistedHistoryByDate.keys)

        val scannedMessages = scanMessagesFromAllChannelsAndThreads(earliestUnpersistedDate)

        val activityHistory = combinePersistedAndScannedHistory(persistedHistoryByDate, scannedMessages)
        return activityHistory to persistedHistoryByDate.keys
    }

    suspend fun persistMissingPostHistory(
        today: LocalDate,
        loadedPostHistory: PostHistory,
        persistedDates: Set<LocalDate>
    ) {
        val startOfRelevantHistory = computeEarliestRelevantDate(today)
        val yesterday = today.minusDays(1)

        // invert the map from UserId -> Dates to Date -> UserIds
        val usersWhoPostedByDate = mutableMapOf<LocalDate, MutableSet<UserId>>()
        loadedPostHistory.forEach { (userId, datesUserPosted) ->
            datesUserPosted.forEach { dateUserPosted ->
                if (dateUserPosted !in usersWhoPostedByDate) {
                    usersWhoPostedByDate[dateUserPosted] = mutableSetOf(userId)
                } else {
                    usersWhoPostedByDate[dateUserPosted]!!.add(userId)
                }
            }
        }

        var date = startOfRelevantHistory
        // doing this sequentially, instead of in parallel, so the persistence channel is easier to read
        while (date <= yesterday) {
            if (date !in persistedDates) {
                logger.debug { "Persisting missing post history for $date" }
                val usersWhoPosted = usersWhoPostedByDate[date]
                // TODO #16: persist to persistence channel
                throw NotImplementedError()
            }
            date = date.plusDays(1)
        }
    }

    /** Fetch the persisted history from the persistence channel */
    private suspend fun fetchPersistedHistoryByDate(): UsersWhoPostedAndReactedByDate {
        // TODO #16: implement
        throw NotImplementedError()
    }

    /** The earliest date we need to consider in order to compute the member's roles correctly */
    private fun computeEarliestRelevantDate(today: LocalDate): LocalDate {
        val daysToLookBack = activeMemberConfig.maxWindowSize
        return today.minusDays(daysToLookBack - 1L)
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

    /** The earliest date we need to scan back to, given what we already have found in the persistence channel */
    private fun computeEarliestUnpersistedDate(today: LocalDate, persistedDates: Set<LocalDate>): LocalDate {
        val earliestRelevantDate = computeEarliestRelevantDate(today)
        var earliestUnpersistedDate = earliestRelevantDate
        while (earliestUnpersistedDate in persistedDates) {
            earliestUnpersistedDate = earliestUnpersistedDate.plusDays(1)
        }
        return earliestUnpersistedDate
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

/** Unlike ActivityHistory, this alternative format of ActivityHistory allows us to know which days have been persisted, even if there was no activity on that day */
private typealias UsersWhoPostedAndReactedByDate = Map<LocalDate, Pair<Set<UserId>, Set<UserId>>>