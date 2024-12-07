package org.regenagcoop.discord.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.RoleChange
import org.regenagcoop.model.RoleChangeHistory
import java.time.Instant
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

    internal fun computePersistedActivityHistory(persistedHistoryMessages: List<Message>): PersistedActivityHistory {
        fun parsePostOrReactionHistoryMessage(text: String, prefix: String): Pair<LocalDate, Set<UserId>> {
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
        val roleChanges = mutableListOf<RoleChange>()

        persistedHistoryMessages.forEach { message ->
            val postPrefix = "Users who posted on "
            val reactionPrefix = "Users who reacted on "
            val roleChangePrefix = "Role change occurred."
            val roleChangeRegex = Regex(
                "$roleChangePrefix (\\d+) transitioned from (\\w+) to (\\w+) at (.+)"
            )

            when {
                message.text.startsWith(postPrefix) -> {
                    val (date, posterIds) = parsePostOrReactionHistoryMessage(message.text, postPrefix)
                    val last = persistedHistoryByDate[date]
                    persistedHistoryByDate[date] = UsersWhoPostedAndReacted(
                        posterIds + (last?.usersWhoPosted ?: setOf()),
                        last?.usersWhoReacted ?: setOf()
                    )
                }
                message.text.startsWith(reactionPrefix) -> {
                    val (date, reactorIds) = parsePostOrReactionHistoryMessage(message.text, reactionPrefix)
                    val last = persistedHistoryByDate[date]
                    persistedHistoryByDate[date] = UsersWhoPostedAndReacted(
                        last?.usersWhoPosted ?: setOf(),
                        reactorIds + (last?.usersWhoReacted ?: setOf())
                    )
                }
                message.text.startsWith(roleChangePrefix) -> {
                    val result = roleChangeRegex.matchEntire(message.text)!!
                    val userId = result.groupValues[1].toULong()
                    fun parseRoleId(num: String) = if (num == "null") null else num.toULong()
                    val fromRoleId = parseRoleId(result.groupValues[2])
                    val toRoleId = parseRoleId(result.groupValues[3])
                    val timestamp = Instant.parse(result.groupValues[4])
                    val roleChange = RoleChange(userId, fromRoleId, toRoleId, timestamp)
                    roleChanges.add(roleChange)
                }
                else -> throw IllegalStateException("Unexpected message in persistence channel: ${message.text}")
            }
        }

        return PersistedActivityHistory(
            persistedHistoryByDate,
            roleChanges
        )
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

data class UsersWhoPostedAndReacted(
    val usersWhoPosted: Set<UserId>,
    val usersWhoReacted: Set<UserId>,
)

/** Unlike ActivityHistory, this alternative format of ActivityHistory allows us to know which days have been persisted, even if there was no activity on that day */
typealias UsersWhoPostedAndReactedByDate = Map<LocalDate, UsersWhoPostedAndReacted>

data class PersistedActivityHistory(
    val usersWhoPostedAndReactedByDate: UsersWhoPostedAndReactedByDate,
    val roleChangeHistory: RoleChangeHistory,
)