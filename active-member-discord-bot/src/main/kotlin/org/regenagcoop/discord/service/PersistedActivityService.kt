package org.regenagcoop.discord.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

class PersistedActivityService(
    private val discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
) {
    private val logger = KotlinLogging.logger { }

    /** Fetch the persisted history from the persistence channel */
    suspend fun fetchPersistedHistoryByDate(): UsersWhoPostedAndReactedByDate {
        // TODO #16: implement
        throw NotImplementedError()
    }

    suspend fun persistPostHistoryForDay(date: LocalDate, usersWhoPostedOnDate: Set<UserId>) {
        val usersStr = usersWhoPostedOnDate.sorted().joinToString { ", " }
        val message = "Users who posted on $date: $usersStr"
        discord.rooms.postMessage(message, activeMemberConfig.persistenceConfig.channel)
    }

    suspend fun persistMissingPostHistory(
        today: LocalDate,
        loadedPostHistory: PostHistory,
        persistedDates: Set<LocalDate>
    ) {
        val startOfRelevantHistory = activeMemberConfig.computeEarliestScanDate(today)
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
                val usersWhoPosted = usersWhoPostedByDate[date] ?: setOf()
                persistPostHistoryForDay(date, usersWhoPosted)
            }
            date = date.plusDays(1)
        }
    }

    /** The earliest date we need to scan back to, given what we already have found in the persistence channel */
    fun computeEarliestUnpersistedDate(today: LocalDate, persistedDates: Set<LocalDate>): LocalDate {
        val earliestRelevantDate = activeMemberConfig.computeEarliestScanDate(today)
        var earliestUnpersistedDate = earliestRelevantDate
        while (earliestUnpersistedDate in persistedDates) {
            earliestUnpersistedDate = earliestUnpersistedDate.plusDays(1)
        }
        return earliestUnpersistedDate
    }
}

/** Unlike ActivityHistory, this alternative format of ActivityHistory allows us to know which days have been persisted, even if there was no activity on that day */
internal typealias UsersWhoPostedAndReactedByDate = Map<LocalDate, Pair<Set<UserId>, Set<UserId>>>