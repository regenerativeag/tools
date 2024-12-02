package org.regenagcoop.discord.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.regenagcoop.ChannelIds
import org.regenagcoop.activeMemberConfig
import org.regenagcoop.discord.mock.DiscordMocker
import java.time.LocalDate

class PersistedActivityServiceTest {
    private val discordMocker = DiscordMocker()
    private val persistedActivityService = PersistedActivityService(discordMocker.mock, activeMemberConfig)

    @Test
    fun readPersistedHistory() = runBlocking {
        // given
        discordMocker.mockMessagesInChannel(ChannelIds.persistenceLog, listOf(
            "Users who posted on 2016-11-08: 1, 2",
            "Users who posted on 2016-11-09: 1, 27",
            "Users who posted on 2016-11-10: 2, 33",
            "Users who posted on 2016-08-01: 3, 5",
            "Users who posted on 2016-08-02: 4, 27, 33",
            "Users who posted on 2022-01-05: "
        ))

        // when
        val persistedHistoryMessages = persistedActivityService.fetchPersistedHistoryMessages()
        val persistedHistory = persistedActivityService.computePersistedHistoryByDate(persistedHistoryMessages)

        // then
        val expected = mapOf(
            LocalDate.of(2016, 11, 8) to UsersWhoPostedAndReacted(
                setOf(1uL, 2uL),
                setOf()
            ),
            LocalDate.of(2016, 11, 9) to UsersWhoPostedAndReacted(
                setOf(1uL, 27uL),
                setOf()
            ),
            LocalDate.of(2016, 11, 10) to UsersWhoPostedAndReacted(
                setOf(2uL, 33uL),
                setOf()
            ),
            LocalDate.of(2016, 8, 1) to UsersWhoPostedAndReacted(
                setOf(3uL, 5uL),
                setOf()
            ),
            LocalDate.of(2016, 8, 2) to UsersWhoPostedAndReacted(
                setOf(4uL, 27uL, 33uL),
                setOf()
            ),
            LocalDate.of(2022, 1, 5) to UsersWhoPostedAndReacted(
                setOf(),
                setOf()
            )
        )

        assertEquals(expected, persistedHistory)
    }
}