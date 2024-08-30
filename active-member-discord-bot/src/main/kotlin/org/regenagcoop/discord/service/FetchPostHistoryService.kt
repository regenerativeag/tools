package org.regenagcoop.discord.service

import kotlinx.coroutines.*
import mu.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

class FetchPostHistoryService(
    discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
    ) : DiscordClient(discord) {
        private val logger = KotlinLogging.logger { }

    /** Query discord for enough recent post history that active member roles can be computed */
    suspend fun fetchPostHistory(today: LocalDate): PostHistory {
        val daysToLookBack = activeMemberConfig.maxWindowSize
        val earliestValidDate = today.minusDays(daysToLookBack - 1L)

        val messages = coroutineScope {
            val messagesFromChannelsAndArchivedThreadsDeferred = async {
                discord.rooms.readMessagesFromTopLevelChannelsInGuild(earliestValidDate)
            }

            val messagesFromActiveThreadsDeferred = async {
                discord.rooms.readMessagesFromActiveThreadsInGuild(earliestValidDate)
            }

            messagesFromChannelsAndArchivedThreadsDeferred.await() + messagesFromActiveThreadsDeferred.await()
        }


        val result = mutableMapOf<UserId, MutableSet<LocalDate>>()
        messages.forEach { message ->
            if (message.userId !in result) {
                result[message.userId] = mutableSetOf(message.utcDate)
            } else {
                result[message.userId]!!.add(message.utcDate)
            }
        }
        return result
    }
}