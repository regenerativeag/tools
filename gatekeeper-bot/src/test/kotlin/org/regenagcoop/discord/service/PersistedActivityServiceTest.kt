package org.regenagcoop.discord.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.regenagcoop.ChannelIds
import org.regenagcoop.activeMemberConfig
import org.regenagcoop.discord.mock.DiscordMocker
import org.regenagcoop.model.RoleChange
import java.time.Instant
import java.time.LocalDate

class PersistedActivityServiceTest {
    private val discordMocker = DiscordMocker()
    private val persistedActivityService = PersistedActivityService(discordMocker.mock, activeMemberConfig)

    @Test
    fun readPersistedHistory() = runBlocking {
        // given
        discordMocker.mockMessagesInChannel(ChannelIds.persistenceLog, listOf(
            "Users who posted on 2016-11-08: 1, 2",
            "Users who reacted on 1940-04-06: 7, 77, 777, 999, 99, 9",
            "Users who posted on 2016-11-09: 1, 27",
            "Role change occurred. 90 transitioned from 12 to 80 at 1888-03-01T06:06:37.828199800Z",
            "Users who posted on 2016-11-10: 2, 33",
            "Role change occurred. 10 transitioned from null to 202 at 2024-12-06T21:51:53.741160247Z",
            "Users who reacted on 2016-11-10: 298, 17, 33",
            "Users who reacted on 2088-11-12: 7",
            "Users who posted on 2016-08-01: 3, 5",
            "Users who reacted on 2016-08-01: 113",
            "Users who posted on 2016-08-02: 4, 27, 33",
            "Users who posted on 2022-01-05: ",
            "Users who posted on 2077-07-07:", // discord trims our trailing space
            "Role change occurred. 189 transitioned from 555 to null at 2021-11-10T22:05:12.777169843Z",
            "Users who reacted on 2017-01-04: 7, 5, 2",
            "Role change occurred. 90 transitioned from 12 to 80 at 1997-03-01T06:06:37.828199800Z",
        ))

        // when
        val persistedHistoryMessages = persistedActivityService.fetchPersistedHistoryMessages()
        val persistedActivityHistory = persistedActivityService.computePersistedActivityHistory(persistedHistoryMessages)

        // then
        val expectedPostsAndReactions = mapOf(
            LocalDate.of(2016, 11, 8) to UsersWhoPostedAndReacted(
                setOf(1uL, 2uL),
                setOf()
            ),
            LocalDate.of(1940, 4, 6) to UsersWhoPostedAndReacted(
                setOf(),
                setOf(7uL, 77uL, 777uL, 999uL, 99uL, 9uL)
            ),
            LocalDate.of(2016, 11, 9) to UsersWhoPostedAndReacted(
                setOf(1uL, 27uL),
                setOf()
            ),
            LocalDate.of(2016, 11, 10) to UsersWhoPostedAndReacted(
                setOf(2uL, 33uL),
                setOf(298uL, 17uL, 33uL)
            ),
            LocalDate.of(2088, 11, 12) to UsersWhoPostedAndReacted(
                setOf(),
                setOf(7uL)
            ),
            LocalDate.of(2016, 8, 1) to UsersWhoPostedAndReacted(
                setOf(3uL, 5uL),
                setOf(113uL)
            ),
            LocalDate.of(2016, 8, 2) to UsersWhoPostedAndReacted(
                setOf(4uL, 27uL, 33uL),
                setOf()
            ),
            LocalDate.of(2022, 1, 5) to UsersWhoPostedAndReacted(
                setOf(),
                setOf()
            ),
            LocalDate.of(2017, 1, 4) to UsersWhoPostedAndReacted(
                setOf(),
                setOf(7uL, 5uL, 2uL)
            ),
            LocalDate.of(2077, 7, 7) to UsersWhoPostedAndReacted(
                setOf(),
                setOf()
            )
        )
        val expectedRoleChanges = mapOf(
            10uL to listOf(RoleChange(10uL, null, 202uL, Instant.parse("2024-12-06T21:51:53.741160247Z"))),
            189uL to listOf(RoleChange(189uL, 555uL, null, Instant.parse("2021-11-10T22:05:12.777169843Z"))),
            90uL to listOf(
                RoleChange(90uL, 12uL, 80uL, Instant.parse("1888-03-01T06:06:37.828199800Z")),
                RoleChange(90uL, 12uL, 80uL, Instant.parse("1997-03-01T06:06:37.828199800Z"))
            ),
        )
        val expected = PersistedActivityHistory(expectedPostsAndReactions, expectedRoleChanges)

        assertEquals(expected, persistedActivityHistory)
    }
}