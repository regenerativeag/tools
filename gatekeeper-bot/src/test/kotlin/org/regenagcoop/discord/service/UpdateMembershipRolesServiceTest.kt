package org.regenagcoop.discord.service

import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.regenagcoop.Database
import org.regenagcoop.MessageIds
import org.regenagcoop.RoleIds
import org.regenagcoop.activeMemberConfig
import org.regenagcoop.discord.mock.DiscordMocker
import org.regenagcoop.discord.model.MessageId
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.TriggeringAction
import org.regenagcoop.model.UserActivityHistory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Random

class UpdateMembershipRolesServiceTest {

    private val discordMocker = DiscordMocker()

    private val database = mockk<Database>()

    private val updateMembershipRolesService = UpdateMembershipRolesService(
        discordMocker.mock,
        MembershipRoleService(discordMocker.mock, activeMemberConfig, database),
        activeMemberConfig,
        database
    )

    enum class ForUserTestCase(
        val given: Given,
        val expectedRoleChange: RoleChange? = null,
    ) {
        REACTED_VISITOR_NO_CHANGE(
            Given(triggeringAction = newReactionAdded())
        ),
        REACTED_GUEST_NO_CHANGE(
            Given(
                currentRoles = listOf(RoleIds.guest),
                triggeringAction = newReactionAdded()
            )
        ),
        REACTED_ACTIVE_MEMBER_NO_CHANGE(
            Given(
                currentRoles = listOf(RoleIds.activeMember),
                triggeringAction = newReactionAdded()
            )
        ),
        REACTED_INACTIVE_NO_CHANGE(
            Given(
                currentRoles = listOf(RoleIds.inactive),
                triggeringAction = newReactionAdded()
            )
        ),
        REACTED_INACTIVE_TO_VISITOR(
            Given(
                currentRoles = listOf(RoleIds.inactive),
                triggeringAction = newReactionAdded(MessageIds.quietGardenMessage)
            ),
            RoleChange(null)
        ),
        REACTED_INACTIVE_MEMBER_NO_CHANGE(
            Given(
                currentRoles = listOf(RoleIds.inactiveMember),
                triggeringAction = newReactionAdded()
            )
        ),
        REACTED_INACTIVE_MEMBER_TO_ACTIVE_MEMBER(
            Given(
                currentRoles = listOf(RoleIds.inactiveMember),
                triggeringAction = newReactionAdded(MessageIds.quietGardenMessage)
            ),
            RoleChange(RoleIds.activeMember)
        ),
        POSTED_VISITOR_TO_GUEST(
            Given(
                triggeringAction = newPostAdded(),
            ),
            RoleChange(RoleIds.guest)
        ),
        POSTED_GUEST_NO_CHANGE_DIFFERENT_DAY(
            Given(
                currentRoles = listOf(RoleIds.guest),
                triggeringAction = newPostAdded()
            )
        ),
        POSTED_GUEST_NO_CHANGE_SAME_DAY(
            Given(
                postHistory = setOf(
                    today.minusDays(1),
                    today.minusDays(2),
                    today.minusDays(3)
                ),
                currentRoles = listOf(RoleIds.guest),
                joinTimestamp = now.minus(4, ChronoUnit.DAYS),
                triggeringAction = newPostAdded(false)
            ),
        ),
        POSTED_GUEST_TO_ACTIVE_MEMBER(
            Given(
                postHistory = setOf(
                    today.minusDays(1),
                    today.minusDays(2),
                    today.minusDays(3)
                ),
                currentRoles = listOf(RoleIds.guest),
                joinTimestamp = now.minus(4, ChronoUnit.DAYS),
                triggeringAction = newPostAdded() // only difference
            ),
            RoleChange(RoleIds.activeMember)
        ),
        POSTED_ACTIVE_MEMBER_NO_CHANGE(
            Given(
                currentRoles = listOf(RoleIds.activeMember),
                triggeringAction = newPostAdded()
            )
        );
    }

    @Nested
    inner class ForUserTest {
        // TODO #26: next -- define and execute test....
        // TODO #26: after that -- define ForAllUsersTest
    }

    @Nested
    inner class ForAllUsersTest {
        // Given: UserActivityHistory, CurrentRoles, JoinTimestamp
        // Expect: RoleChange?
        val testData = null
        // INACTIVE_NO_CHANGE
        // INACTIVE_MEMBER_NO_CHANGE
        // INACTIVE_MEMBER_TO_INACTIVE
            // TODO #26: List additional roles (colors) to remove
        // VISITOR_NO_CHANGE_BY_POSTS
        // VISITOR_NO_CHANGE_BY_REACTIONS
        // VISITOR_TO_INACTIVE
        // VISITOR_TO_GUEST
        // GUEST_NO_CHANGE_BY_POSTS
        // GUEST_NO_CHANGE_BY_REACTIONS
        // GUEST_TO_INACTIVE
        // GUEST_TO_ACTIVE_MEMBER
        // ACTIVE_MEMBER_NO_CHANGE_BY_POSTS
        // ACTIVE_MEMBER_NO_CHANGE_BY_REACTIONS
        // ACTIVE_MEMBER_TO_INACTIVE_MEMBER

    }

    data class RoleChange(
        val toRoleId: RoleId? = null
    )

    data class Given(
        val userId: UserId = randomULong(),
        val userActivityHistory: UserActivityHistory = UserActivityHistory(userId, setOf(), setOf(), listOf()),
        val currentRoles: List<RoleId> = listOf(),
        val joinTimestamp: Instant = now,
        val triggeringAction: TriggeringAction? = null,
    ) {
        constructor(
            postHistory: Set<LocalDate>,
            currentRoles: List<RoleId>,
            joinTimestamp: Instant,
            triggeringAction: TriggeringAction,
            userId: UserId = randomULong()
        ) : this(
            userId = userId,
            userActivityHistory = UserActivityHistory(userId, postHistory, setOf(), listOf()),
            currentRoles = currentRoles,
            joinTimestamp = joinTimestamp,
            triggeringAction = triggeringAction
        )
    }

    companion object {
        private val now = Instant.now()
        private val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        private val random = Random()
        private fun randomULong() = random.nextLong(1L, 999_999_999L).toULong()
        private fun newReactionAdded(messageId: MessageId = randomULong()) = TriggeringAction.ReactionAdded(messageId, "👍")
        private fun newPostAdded(isFirstPostOfDay: Boolean = true) = TriggeringAction.PostAdded(isFirstPostOfDay)
    }
}