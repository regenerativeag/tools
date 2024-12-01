package org.regenagcoop.discord.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.regenagcoop.ChannelIds
import org.regenagcoop.activeMemberConfig
import org.regenagcoop.discord.mock.CapturedMessage
import org.regenagcoop.discord.mock.DiscordMocker
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

class PersistedActivityServiceTest {
    private val discordMocker = DiscordMocker()
    private val persistedActivityService = PersistedActivityService(discordMocker.mock, activeMemberConfig)

    @Test
    fun persistPostHistoryForDayTest() = runBlocking {
        // given
        val today = LocalDate.of(1703, 9, 30)
        val usersWhoPosted = setOf(5uL, 0uL, 10uL, 7777777uL)

        // when
        persistedActivityService.persistPostHistoryForDay(today, usersWhoPosted)

        // then
        discordMocker.assertMessagesPostedEquals(
            CapturedMessage("Users who posted on 1703-09-30: 0, 5, 10, 7777777", ChannelIds.persistenceLog)
        )
    }

    @Test
    fun persist_No_PostHistoryForDayTest() = runBlocking {
        // given
        val today = LocalDate.of(1703, 9, 30)

        // when
        persistedActivityService.persistPostHistoryForDay(today, setOf())

        // then
        discordMocker.assertMessagesPostedEquals(
            CapturedMessage("Users who posted on 1703-09-30: ", ChannelIds.persistenceLog)
        )
    }


    @Nested
    inner class PersistMissingPostHistory {
        private val userId1 = 2uL
        private val userId2 = 11uL
        private val userId3 = 48uL

        private val date1 = LocalDate.of(2003, 2, 4)
        private val date2 = LocalDate.of(2003, 2, 5)
        private val date3 = LocalDate.of(2003, 2, 6)
        private val today = LocalDate.of(2003, 2, 7)

        private val loadedPostHistory: PostHistory = mutableMapOf(
            userId1 to setOf(date1, date3),
            userId2 to setOf(date2, date3),
            userId3 to setOf(date3, today)
        )

        private val allMessages = listOf(
            CapturedMessage("Users who posted on 2003-02-04: 2", ChannelIds.persistenceLog),
            CapturedMessage("Users who posted on 2003-02-05: 11", ChannelIds.persistenceLog),
            CapturedMessage("Users who posted on 2003-02-06: 2, 11, 48", ChannelIds.persistenceLog),
            // Today's post history won't be recorded until tomorrow
        )

        @ParameterizedTest
        @ValueSource(ints = [0, 1, 2, 3])
        /** Simulates what would happen if the bot started up for the first time in a few days */
        fun reloadTest(persistedCount: Int) = runBlocking {
            // given
            val persistedDates = listOf(date1, date2, date3).take(persistedCount).toSet()

            // when
            persistedActivityService.persistMissingPostHistory(today, loadedPostHistory, persistedDates)

            // then
            val missingDays = allMessages.size - persistedCount
            val emptyMessages = computeExpectedEmptyMessages(today, loadedPostHistory, persistedDates)
            val missingMessages = emptyMessages + allMessages.takeLast(missingDays)
            discordMocker.assertMessagesPostedEquals(*missingMessages.toTypedArray())
        }

        @Test
        /** Simulates what would happen if the user updated the config so more past data is cared about. In this case, we expect old messages to be posted to the room */
        fun updateConfigTest() = runBlocking {
            // given
            val oldUser1 = 87uL
            val oldUser2 = 6uL
            val oldDate1 = LocalDate.of(2003, 1, 9)
            val oldDate2 = LocalDate.of(2003, 1, 10)

            val loadedPostHistory = mapOf(
                oldUser1 to setOf(oldDate1, oldDate2),
                oldUser2 to setOf(oldDate1),
                userId1 to (loadedPostHistory[userId1]!! + setOf(oldDate1)),
                userId2 to loadedPostHistory[userId2]!!,
                userId3 to (loadedPostHistory[userId3]!! + setOf(oldDate1, oldDate2)),
            )

            val persistedDates = setOf(date1, date2)

            // when
            persistedActivityService.persistMissingPostHistory(today, loadedPostHistory, persistedDates)

            // then
            val emptyMessages = computeExpectedEmptyMessages(today, loadedPostHistory, persistedDates)
            val missingMessages = (emptyMessages + listOf(
                CapturedMessage("Users who posted on 2003-01-09: 2, 6, 48, 87", ChannelIds.persistenceLog),
                CapturedMessage("Users who posted on 2003-01-10: 48, 87", ChannelIds.persistenceLog),
            ) + allMessages.last()).sortedBy { it.text }

            discordMocker.assertMessagesPostedEquals(*missingMessages.toTypedArray())
        }

        private fun computeExpectedEmptyMessages(
            today: LocalDate,
            postHistory: PostHistory,
            persistedDates: Set<LocalDate>,
        ): List<CapturedMessage> {
            val emptyMessages = mutableListOf<CapturedMessage>()
            val nonEmptyDays = (postHistory.values.flatten() + persistedDates).toSet()
            var day = activeMemberConfig.computeEarliestScanDate(today)
            while (day != today) {
                if (day !in nonEmptyDays) {
                    emptyMessages.add(CapturedMessage("Users who posted on $day: ", ChannelIds.persistenceLog))
                }
                day = day.plusDays(1)
            }
            return emptyMessages
        }
    }

    @Test
    fun readPersistedHistory() = runBlocking {
        // given
        discordMocker.mockMessagesInChannel(ChannelIds.persistenceLog, listOf(
            "Users who posted on 2016-11-08: 1, 2",
            "Users who posted on 2016-11-09: 1, 27",
            "Users who posted on 2016-11-10: 2, 33",
            "Users who posted on 2016-08-01: 3, 5",
            "Users who posted on 2016-08-02: 4, 27, 33",
        ))

        // when
        val persistedHistory = persistedActivityService.fetchPersistedHistoryByDate()

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
            )
        )

        assertEquals(expected, persistedHistory)
    }
}