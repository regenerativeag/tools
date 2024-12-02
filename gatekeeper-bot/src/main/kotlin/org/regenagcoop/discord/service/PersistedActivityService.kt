package org.regenagcoop.discord.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

class PersistedActivityService(
    private val discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
) {
    private val logger = KotlinLogging.logger { }

    suspend fun fetchPersistedHistoryMessages(): List<Message> {
        val persistedHistoryMessages = discord.rooms.readMessagesFromChannel(
            activeMemberConfig.persistenceConfig.channel,
            null
        )
        logger.debug { "Found ${persistedHistoryMessages.size} messages in persistence channel" }
        return persistedHistoryMessages
    }

    internal fun computePersistedHistoryByDate(persistedHistoryMessages: List<Message>): UsersWhoPostedAndReactedByDate {
        val persistedHistoryByDate = mutableMapOf<LocalDate, UsersWhoPostedAndReacted>()

        persistedHistoryMessages.forEach { message ->
            val postPrefix = "Users who posted on "
            val datePlaceholder = "XXXX-XX-XX"
            val separator  = ": "
            when {
                message.text.startsWith(postPrefix) -> {
                    var remainder = message.text
                    remainder = remainder.substring(postPrefix.length)
                    val dateStr = remainder.substring(0, datePlaceholder.length)
                    remainder = remainder.substring(datePlaceholder.length + separator.length)
                    val usersStr = remainder

                    val date = LocalDate.parse(dateStr)
                    val posterIds = if (usersStr.isBlank()) {
                        setOf()
                    } else {
                        usersStr.split(", ").map { it.toULong() }.toSet()
                    }

                    val last = persistedHistoryByDate[date]
                    persistedHistoryByDate[date] = UsersWhoPostedAndReacted(
                        posterIds + (last?.usersWhoPosted ?: setOf()),
                        last?.usersWhoReacted ?: setOf()
                    )
                }
                else -> throw IllegalStateException("Unexpected message in persistence channel: ${message.text}")
            }

        }

        return persistedHistoryByDate
    }

    // TODO cleanup: extract to own parallel class of PersistReactionService
    suspend fun persistPostHistoryForDay(date: LocalDate, usersWhoPostedOnDate: Set<UserId>) {
        val usersStr = usersWhoPostedOnDate.sorted().joinToString()
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
                val usersWhoPosted = usersWhoPostedByDate[date] ?: setOf()
                logger.debug { "Persisting missing post history for $date" }
                persistPostHistoryForDay(date, usersWhoPosted)
            }
            date = date.plusDays(1)
        }
    }

    /** The earliest date we need to scan back to, given what we already have found in the persistence channel */
    fun computeEarliestUnpersistedDate(today: LocalDate, persistedDates: Set<LocalDate>): LocalDate {
        // Example demonstrating why we don't merely take the max(persistedDates):
        //
        // Yesterday, the server was configured to only care about the last 30 days of history, but after a config
        //   edit, the server now cares about the last 180 days of history. Depending on how many days of history
        //   have been persisted, it is possible that there's only 30 days of history in the persistence channel, but
        //   now 180 days of history are needed. In this case, we would need to back-fill the missing 150 days of
        //   history.
        //
        // In this scenario, the bot will fetch history that wasn't needed before, and publish history into the
        //   persistence out of order. There is no issues with this, if we scan the full persistence channel and don't
        //   assume the channel is in order.
        val earliestRelevantDate = activeMemberConfig.computeEarliestScanDate(today)
        var earliestUnpersistedDate = earliestRelevantDate
        while (earliestUnpersistedDate in persistedDates) {
            earliestUnpersistedDate = earliestUnpersistedDate.plusDays(1)
        }
        return earliestUnpersistedDate
    }
}

internal data class UsersWhoPostedAndReacted(
    val usersWhoPosted: Set<UserId>,
    val usersWhoReacted: Set<UserId>,
)

/** Unlike ActivityHistory, this alternative format of ActivityHistory allows us to know which days have been persisted, even if there was no activity on that day */
internal typealias UsersWhoPostedAndReactedByDate = Map<LocalDate, UsersWhoPostedAndReacted>