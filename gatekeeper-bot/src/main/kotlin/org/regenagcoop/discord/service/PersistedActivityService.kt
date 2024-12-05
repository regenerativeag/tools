package org.regenagcoop.discord.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
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
        fun parseHistoryMessage(text: String, prefix: String): Pair<LocalDate, Set<UserId>> {
            val datePlaceholder = "XXXX-XX-XX"
            val separator  = ": "

            var remainder = text
            remainder = remainder.substring(prefix.length)
            val dateStr = remainder.substring(0, datePlaceholder.length)
            remainder = remainder.substring(datePlaceholder.length + separator.length)
            val usersStr = remainder

            val date = LocalDate.parse(dateStr)
            val userIds = if (usersStr.isBlank()) {
                setOf()
            } else {
                usersStr.split(", ").map { it.toULong() }.toSet()
            }

            return date to userIds
        }

        val persistedHistoryByDate = mutableMapOf<LocalDate, UsersWhoPostedAndReacted>()

        persistedHistoryMessages.forEach { message ->
            val postPrefix = "Users who posted on "
            val reactionPrefix = "Users who reacted on "
            when {
                message.text.startsWith(postPrefix) -> {
                    val (date, posterIds) = parseHistoryMessage(message.text, postPrefix)
                    val last = persistedHistoryByDate[date]
                    persistedHistoryByDate[date] = UsersWhoPostedAndReacted(
                        posterIds + (last?.usersWhoPosted ?: setOf()),
                        last?.usersWhoReacted ?: setOf()
                    )
                }
                message.text.startsWith(reactionPrefix) -> {
                    val (date, reactorIds) = parseHistoryMessage(message.text, reactionPrefix)
                    val last = persistedHistoryByDate[date]
                    persistedHistoryByDate[date] = UsersWhoPostedAndReacted(
                        last?.usersWhoPosted ?: setOf(),
                        reactorIds + (last?.usersWhoReacted ?: setOf())
                    )
                }
                else -> throw IllegalStateException("Unexpected message in persistence channel: ${message.text}")
            }
        }

        return persistedHistoryByDate
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