package org.regenagcoop.discord.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.config.ActiveMemberConfig
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

class PersistPostsService(
    private val discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
) {
    private val logger = KotlinLogging.logger { }

    suspend fun persistPostHistoryForDay(date: LocalDate, usersWhoPostedOnDate: Set<UserId>) {
        val usersStr = usersWhoPostedOnDate.sorted().joinToString()
        val message = "Users who posted on $date: $usersStr"
        discord.rooms.postMessage(message, activeMemberConfig.persistenceConfig.channel)
    }

    suspend fun persistMissingPostHistory(
        today: LocalDate,
        loadedPostHistory: PostHistory,
        persistedPostDates: Set<LocalDate>
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
            if (date !in persistedPostDates) {
                val usersWhoPosted = usersWhoPostedByDate[date] ?: setOf()
                logger.debug { "Persisting missing post history for $date" }
                persistPostHistoryForDay(date, usersWhoPosted)
            }
            date = date.plusDays(1)
        }
    }
}