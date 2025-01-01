package org.regenagcoop.discord.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.regenagcoop.ChannelIds
import org.regenagcoop.UserIds
import org.regenagcoop.activeMemberConfig
import org.regenagcoop.discord.ActiveMemberDiscordBot
import org.regenagcoop.discord.mock.CapturedMessage
import org.regenagcoop.discord.mock.DiscordMocker
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.assertTrue

class PersistReactionServiceTest {
    private val discordMocker = DiscordMocker()

    private val today = ActiveMemberDiscordBot.getTodaysDate()

    enum class DaysDefined {
        YESTERDAY_AND_TODAY,
        YESTERDAY_ONLY,
        TODAY_ONLY,
        NO_DAYS
    }

    enum class Day(val offsetDays: Long) {
        YESTERDAY_MINUS_ONE(-2),
        YESTERDAY(-1),
        TODAY(0),
        TOMORROW(1),
        TOMORROW_PLUS_ONE(2)
    }

    enum class LocateTestCase(
        val daysDefined: DaysDefined,
        val yesterdayExpectation: CapturedMessage.Type, // what we expect to happen when we post to yesterday
        val todayExpectation: CapturedMessage.Type, // what we expect to happen when we post to today
    ) {
        YESTERDAY_AND_TODAY_DEFINED(
            DaysDefined.YESTERDAY_AND_TODAY,
            CapturedMessage.Type.EDIT,
            CapturedMessage.Type.EDIT
        ),
        ONLY_YESTERDAY_DEFINED(
            DaysDefined.YESTERDAY_ONLY,
            CapturedMessage.Type.EDIT,
            CapturedMessage.Type.CREATE
        ),
        ONLY_TODAY_DEFINED(
            DaysDefined.TODAY_ONLY,
            CapturedMessage.Type.CREATE,
            CapturedMessage.Type.EDIT
        ),
        NO_DAYS_DEFINED(
            DaysDefined.NO_DAYS,
            CapturedMessage.Type.CREATE,
            CapturedMessage.Type.CREATE
        )
    }

    @ParameterizedTest
    @EnumSource(LocateTestCase::class)
    fun `locates and adds to or creates yesterday and today messages correctly`(
        case: LocateTestCase
    ) = runBlocking {
        // given
        val daysToPostTo = listOf(Day.YESTERDAY, Day.TODAY).shuffled()
        val messagesInHistoryChannel = generateMessagesFor(case.daysDefined)
        val service = PersistReactionService(discordMocker.mock, activeMemberConfig, today, messagesInHistoryChannel)
        val userIds = listOf(222uL, 888uL).shuffled()

        // when
        daysToPostTo.zip(userIds).forEach { (dayToPostTo, userId) ->
            val date = today.plusDays(dayToPostTo.offsetDays)
            service.persistReaction(date, userId)
        }

        // then
        val expectedMessages = daysToPostTo.zip(userIds).map { (dayToPostTo, userId) ->
            val expectation = when (dayToPostTo) {
                Day.YESTERDAY -> case.yesterdayExpectation
                Day.TODAY -> case.todayExpectation
                else -> throw IllegalArgumentException("Test misconfigured")
            }
            generateExpectedCapturedMessageFor(dayToPostTo, userId, expectation)
        }

        discordMocker.assertCapturedMessagesEqual(*expectedMessages.toTypedArray())
    }

    @ParameterizedTest
    @EnumSource(Day::class)
    fun `posting message out of bounds throws error`(dayToPostTo: Day) = runBlocking {
        // given
        val daysDefined = DaysDefined.entries.random()
        val messagesInHistoryChannel = generateMessagesFor(daysDefined)
        val service = PersistReactionService(discordMocker.mock, activeMemberConfig, today, messagesInHistoryChannel)
        val userId = 515uL
        val date = today.plusDays(dayToPostTo.offsetDays)

        // when
        val result = runCatching {
            service.persistReaction(date, userId)
        }

        // then
        when (dayToPostTo) {
            Day.TODAY, Day.YESTERDAY, Day.TOMORROW, Day.TOMORROW_PLUS_ONE -> assertTrue(result.isSuccess)
            Day.YESTERDAY_MINUS_ONE -> assertTrue(result.isFailure)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 100])
    fun `posting a message to a future date results in that day being today`(offsetDays: Long) = runBlocking {
        // given
        val daysDefined = DaysDefined.entries.random()
        val messagesInHistoryChannel = generateMessagesFor(daysDefined)
        val service = PersistReactionService(discordMocker.mock, activeMemberConfig, today, messagesInHistoryChannel)
        val userId = 52uL
        val dayToPostTo = today.plusDays(offsetDays)

        // when
        service.persistReaction(dayToPostTo, userId)

        // then
        val expectedMessage = if (
            offsetDays == 0L
            && daysDefined in setOf(DaysDefined.TODAY_ONLY, DaysDefined.YESTERDAY_AND_TODAY)
        ) {
            CapturedMessage("Users who reacted on $today: 9, 10, 52", ChannelIds.persistenceLog, CapturedMessage.Type.EDIT)
        } else {
            CapturedMessage("Users who reacted on $dayToPostTo: 52", ChannelIds.persistenceLog)

        }
        discordMocker.assertCapturedMessagesEqual(expectedMessage)
    }


    enum class TomorrowTestCase(
        val daysDefined: DaysDefined,
        val oldTodayExpectation: CapturedMessage.Type, // after creating a new today by adding a reaction with tomorrow's date, what do we expect to happen when we post to the old today (i.e. the new yesterday)?
    ) {
        YESTERDAY_AND_TODAY_DEFINED(
            DaysDefined.YESTERDAY_AND_TODAY,
            CapturedMessage.Type.EDIT
        ),
        ONLY_YESTERDAY_DEFINED(
            DaysDefined.YESTERDAY_ONLY,
            CapturedMessage.Type.CREATE
        ),
        ONLY_TODAY_DEFINED(
            DaysDefined.TODAY_ONLY,
            CapturedMessage.Type.EDIT
        ),
        NO_DAYS_DEFINED(
            DaysDefined.NO_DAYS,
            CapturedMessage.Type.CREATE
        )
    }

    @ParameterizedTest
    @EnumSource(TomorrowTestCase::class)
    fun `posting message for tomorrow correctly performs swap, modifying yesterday and today`(
        case: TomorrowTestCase
    ) = runBlocking {
        // given
        val messagesInHistoryChannel = generateMessagesFor(case.daysDefined)
        val service = PersistReactionService(discordMocker.mock, activeMemberConfig, today, messagesInHistoryChannel)
        val daysToPostTo = listOf(Day.TODAY, Day.YESTERDAY, Day.TOMORROW).shuffled()
        val userId1 = 10072uL
        val userId2 = 3003uL

        // when:
        // (1) post a new reaction for tomorrow, causing a swap
        service.persistReaction(today.plusDays(1), userId1)
        // (2) posting a new reaction to the old today, old yesterday, and new today in random order (shuffled above)
        val results = daysToPostTo.map {
            runCatching {
                service.persistReaction(today.plusDays(it.offsetDays), userId2)
            }
        }

        // then: ensure the correct history messages were created or edited
        // adding reactions to the old yesterday will now throw an error
        val oldYesterdayIdx = daysToPostTo.indexOf(Day.YESTERDAY)
        results.forEachIndexed { idx, result ->
            if (idx == oldYesterdayIdx) {
                assertTrue(result.isFailure)
            } else {
                assertTrue(result.isSuccess)
            }
        }
        // adding reactions to the new today (tomorrow), the new yesterday (today) will still succeed
        val newTodayExpectedText = "Users who reacted on ${today.plusDays(1)}: $userId1"
        val newTodayExpectedMessage = capturedMessageOf(newTodayExpectedText, CapturedMessage.Type.CREATE)
        val successfulExpectedMessagesFromSecondUser = daysToPostTo.filter { it != Day.YESTERDAY }.map {
            when (it) {
                // when posting to today (the new yesterday), expect create/edit based on test case
                Day.TODAY -> generateExpectedCapturedMessageFor(Day.TODAY, userId2, case.oldTodayExpectation)
                // when posting to tomorrow (the new today), we always expect an edit of the history record
                Day.TOMORROW -> capturedMessageOf("$newTodayExpectedText, $userId2", CapturedMessage.Type.EDIT)
                else -> throw IllegalArgumentException("Test misconfigured")
            }
        }
        val expectedMessages = listOf(newTodayExpectedMessage) + successfulExpectedMessagesFromSecondUser
        discordMocker.assertCapturedMessagesEqual(*expectedMessages.toTypedArray())
    }


    private fun generateMessagesFor(daysDefined: DaysDefined): List<Message> {
        val irrelevantMessages = listOf(
            // only reaction days are relevant
            "Users who posted on $today: 1, 2",
            "Users who posted on ${today.minusDays(1)}: 3, 4",
            // only today and yesterday are relevant
            "Users who reacted on ${today.minusDays(2)}: 5, 6",
            "Users who reacted on ${today.plusDays(1)}: 7, 8",
        )
        val todayMessage = "Users who reacted on $today: 9, 10"
        val yesterdayMessage = "Users who reacted on ${today.minusDays(1)}: 11, 12"

        return when (daysDefined) {
            DaysDefined.NO_DAYS -> irrelevantMessages.shuffled()
            DaysDefined.TODAY_ONLY -> (irrelevantMessages + todayMessage).shuffled()
            DaysDefined.YESTERDAY_ONLY -> (irrelevantMessages + yesterdayMessage).shuffled()
            DaysDefined.YESTERDAY_AND_TODAY -> (irrelevantMessages + listOf(todayMessage, yesterdayMessage)).shuffled()
        }.map {
            Message(activeMemberConfig.persistenceConfig.channel, Random.Default.nextULong(), UserIds.gatekeeperBot, Clock.System.now(), it)
        }
    }

    private fun generateExpectedCapturedMessageFor(
        day: Day,
        userId: UserId,
        expectedMessageType: CapturedMessage.Type
    ): CapturedMessage {
        val text = when (expectedMessageType) {
            CapturedMessage.Type.CREATE -> "Users who reacted on ${today.plusDays(day.offsetDays)}: $userId"
            CapturedMessage.Type.EDIT -> when (day) {
                Day.TODAY -> "Users who reacted on $today: 9, 10, $userId"
                Day.YESTERDAY -> "Users who reacted on ${today.minusDays(1)}: 11, 12, $userId"
                else -> throw IllegalArgumentException(day.toString())
            }
        }
        return capturedMessageOf(text, expectedMessageType)
    }

    private fun capturedMessageOf(text: String, expectedMessageType: CapturedMessage.Type)
    = CapturedMessage(text, activeMemberConfig.persistenceConfig.channel, expectedMessageType)
}