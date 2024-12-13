package org.regenagcoop.discord

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.regenagcoop.discord.ActiveMemberDiscordBot
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.AddRoleConfig
import org.regenagcoop.model.KeepRoleConfig
import org.regenagcoop.model.PostHistory
import org.regenagcoop.discord.model.UserId
import java.time.LocalDate
import kotlin.test.assertEquals


class ActiveMemberDiscordBotTest {

    enum class ComputeActiveMembersCase(
        val postHistory: PostHistory = mapOf(),
        val expectedActive: Set<UserId> = setOf(),
        val expectedGuest: Set<UserId> = setOf(),
        val config: ActiveMemberConfig = ActiveMemberConfig(
                    0uL,
                    setOf(7uL),
                    listOf(
                        // Guest
                        ActiveMemberConfig.RoleConfig(
                            0uL,
                            AddRoleConfig(
                                30,
                                1,
                            ),
                            KeepRoleConfig(
                                30,
                                1,
                            ),
                            ActiveMemberConfig.WelcomeMessageConfig(
                                0uL,
                                "start",
                                "end"
                            )
                        ),
                        // Active
                        ActiveMemberConfig.RoleConfig(
                            0uL,
                            AddRoleConfig(
                                10,
                                3,
                            ),
                            KeepRoleConfig(
                                20,
                                2,
                            ),
                            ActiveMemberConfig.WelcomeMessageConfig(
                                0uL,
                                "start",
                                "end"
                            )
                        )
                    ),
                    ActiveMemberConfig.DowngradeMessageConfig(0uL, "template"),
                    ActiveMemberConfig.PersistenceConfig(0uL),
            )
    ) {
        EMPTY(),
        TOO_FAR_BACK(
            mapOf(1uL to setOf(
                today.minusDays(61),
                today.minusDays(62),
                today.minusDays(70),
            ))
        ),
        GUEST_NOT_ENOUGH_FOR_ACTIVE(mapOf(1uL to setOf(today)),
            expectedGuest = setOf(1uL)
        ),
        GUEST_TOO_FAR_BACK_FOR_ACTIVE(
            mapOf(1uL to setOf(
                    today.minusDays(8),
                    today.minusDays(9),
                    today.minusDays(10)
            )),
            expectedGuest = setOf(1uL),
        ),
        ACTIVE(
            mapOf(1uL to setOf(
                    today.minusDays(7),
                    today.minusDays(8),
                    today.minusDays(9)
            )),
            expectedActive = setOf(1uL),
        ),
        EXCLUDE(
            listOf(6, 7, 8).associate { userId ->
                userId.toULong() to listOf(1, 2, 3, 4).map { today.minusDays(it.toLong()) }.toSet()
            },
            expectedActive = setOf(6uL, 8uL)
        ),
        ALL(
            mapOf(
                1uL to setOf( // not enough (guest)
                        today.minusDays(18),
                        today.minusDays(19)
                ),
                2uL to setOf( // active
                        today,
                        today.minusDays(1),
                        today.minusDays(2)
                ),
                3uL to setOf( // guest
                        today,
                        today.minusDays(1)
                ),
                4uL to setOf( // guest
                        today.minusDays(29),
                ),
                5uL to setOf( // too far back for guest
                        today.minusDays(31)
                ),
                // Bot
                7uL to listOf(1, 2, 3, 4, 5).map { today.minusDays(it.toLong()) }.toSet(),
            ),
            expectedActive = setOf(2uL),
            expectedGuest = setOf(1uL, 3uL, 4uL),
        )
    }

    @ParameterizedTest
    @EnumSource(ComputeActiveMembersCase::class)
    fun testComputeActiveMembers(case: ComputeActiveMembersCase) {
        val activeMemberSets = ActiveMemberDiscordBot.computeActiveMembers(
                case.postHistory,
                today,
                case.config
        )
        assertEquals(2, activeMemberSets.size)
        assertEquals(case.expectedGuest, activeMemberSets[0])
        assertEquals(case.expectedActive, activeMemberSets[1])
    }

    companion object {
        val today = ActiveMemberDiscordBot.getTodaysDate()
    }
}