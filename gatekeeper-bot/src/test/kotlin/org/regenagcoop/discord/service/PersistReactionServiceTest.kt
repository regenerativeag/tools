package org.regenagcoop.discord.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.regenagcoop.UserIds
import org.regenagcoop.activeMemberConfig
import org.regenagcoop.discord.ActiveMemberDiscordBot
import org.regenagcoop.discord.mock.CapturedMessage
import org.regenagcoop.discord.mock.DiscordMocker
import org.regenagcoop.discord.model.Message
import kotlin.random.Random
import kotlin.random.nextULong

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

    @ParameterizedTest
    @EnumSource(DaysDefined::class)
    // case: Yesterday and Today
    // random order: add to yesterday, add to today

    // case: Yesterday, no Today
    // random order: add to yesterday, create today

    // case: no Yesterday, Today
    // random order: create yesterday, add to today

    // case: no Yesterday and no Today
    // random order: create yesterday, create today
    fun `locates yesterday and today messages correctly`(daysDefined: DaysDefined) = runBlocking {
        // given
        val daysToPostTo = listOf(Day.YESTERDAY, Day.TODAY).shuffled()
        val messagesInHistoryChannel = generateMessagesFor(daysDefined)
        val service = PersistReactionService(discordMocker.mock, activeMemberConfig, today, messagesInHistoryChannel)
        val userIds = listOf(222uL, 888uL).shuffled()

        // when
        daysToPostTo.zip(userIds).forEach { (dayToPostTo, userId) ->
            val date = today.plusDays(dayToPostTo.offsetDays)
            service.persistReaction(date, userId)
        }

        // then
        val expectedMessages = daysToPostTo.zip(userIds).map { (dayToPostTo, userId) ->
            val messageType = when (daysDefined) {
                DaysDefined.NO_DAYS -> CapturedMessage.Type.CREATE
                DaysDefined.TODAY_ONLY -> if (dayToPostTo == Day.TODAY) CapturedMessage.Type.EDIT else CapturedMessage.Type.CREATE
                DaysDefined.YESTERDAY_ONLY -> if (dayToPostTo == Day.YESTERDAY) CapturedMessage.Type.EDIT else CapturedMessage.Type.CREATE
                DaysDefined.YESTERDAY_AND_TODAY -> CapturedMessage.Type.EDIT
            }
            val date = today.plusDays(dayToPostTo.offsetDays)
            val text = if (messageType == CapturedMessage.Type.CREATE) {
                "Users who reacted on $date: $userId"
            } else {
                when (dayToPostTo) {
                    Day.TODAY -> "Users who reacted on $date: 9, 10, $userId"
                    Day.YESTERDAY -> "Users who reacted on $date: 11, 12, $userId"
                    else -> throw IllegalArgumentException("Test misconfigured")
                }
            }
            CapturedMessage(text, activeMemberConfig.persistenceConfig.channel, messageType)
        }

        discordMocker.assertCapturedMessagesEqual(*expectedMessages.toTypedArray())
    }

    // TODO test: posting message out of bounds throws error
    // case: Today & Yesterday defined
    // post-day=yesterday-1=error
    // post-day=yesterday=ok
    // post-day=today=ok
    // post-day=tomorrow=ok
    // post-day=tomorrow+1=error

    // case: only Yesterday defined
    // post-day=yesterday-1=error
    // post-day=yesterday=ok
    // post-day=today=ok
    // post-day=tomorrow=ok
    // post-day=tomorrow+1=error

    // case: only Today defined
    // post-day=yesterday-1=error
    // post-day=yesterday=ok
    // post-day=today=ok
    // post-day=tomorrow=ok
    // post-day=tomorrow+1=error

    // case: no days defined
    // post-day=yesterday-1=error
    // post-day=yesterday=ok
    // post-day=today=ok
    // post-day=tomorrow=ok
    // post-day=tomorrow+1=error


    // TODO test: posting message for "tomorrow" does swap correctly
    // case: Today & Yesterday defined
    // post to tomorrow->create new today
    // random order: post to old today->add. post to old yesterday->error. post to new today->add.

    // case: only yesterday defined
    // post to tomorrow->create new today
    // random order: post to old today->create. post to old yesterday->error. post to new today->add.

    // case: only today defined
    // post to tomorrow->create new today
    // random order: post to old today->add. post to old yesterday->error. post to new today->add.

    // case: no days defined
    // post to tomorrow->create new today
    // random order: post to old today->create. post to old yesterday->error. post to new today->add.

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
}