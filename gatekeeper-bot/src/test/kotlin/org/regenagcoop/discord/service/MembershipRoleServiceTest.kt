package org.regenagcoop.discord.service

import dev.kord.common.entity.DiscordGuildMember
import dev.kord.common.entity.DiscordRole
import dev.kord.common.entity.DiscordUser
import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.GuildService
import dev.kord.rest.service.RestClient
import dev.kord.rest.service.UserService
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.regenagcoop.*
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.discord.mock.CapturedMessage
import org.regenagcoop.discord.mock.DiscordMocker
import org.regenagcoop.model.ActivityHistory
import org.regenagcoop.model.Qualification
import java.time.LocalDate


class MembershipRoleServiceTest {

    enum class AddAndRemovalTestCase(
        val userId: UserId,
        val currentRoleIds: List<RoleId>,
        val newRoleId: RoleId?,
        val expectedMessages: List<CapturedMessage>,
        val username: String = "Zelda",
    ) {
        VISITOR_TO_GUEST(
            1uL,
            listOf(),
            RoleIds.guest,
            listOf(
                CapturedMessage("Role change occurred. 1 transitioned from null to ${RoleIds.guest} at MATCH_NOW_TIMESTAMP", ChannelIds.persistenceLog),
                CapturedMessage("A warm hello to our newest guest, <@1> :relaxed: Please check out our [Community Guide](https://regenagcoop.org/community-guide/) when you have a moment.", ChannelIds.connect),
            )
        ),
        GUEST_TO_VISITOR(
            2uL,
            listOf(RoleIds.guest),
            null,
            listOf(
                CapturedMessage("Role change occurred. 2 transitioned from ${RoleIds.guest} to null at MATCH_NOW_TIMESTAMP", ChannelIds.persistenceLog),
                CapturedMessage("Zelda has transitioned from Guest to Visitor (no role).", ChannelIds.moderationLog),
            )
        ),
        GUEST_TO_ACTIVE_MEMBER(
            3uL,
            listOf(RoleIds.guest),
            RoleIds.activeMember,
            listOf(
                CapturedMessage("Role change occurred. 3 transitioned from ${RoleIds.guest} to ${RoleIds.activeMember} at MATCH_NOW_TIMESTAMP", ChannelIds.persistenceLog),
                CapturedMessage("Welcome to our community, <@3>!", ChannelIds.community),
            )
        ),
        ACTIVE_MEMBER_TO_GUEST(
            4uL,
            listOf(RoleIds.activeMember),
            RoleIds.guest,
            listOf(
                CapturedMessage("Role change occurred. 4 transitioned from ${RoleIds.activeMember} to ${RoleIds.guest} at MATCH_NOW_TIMESTAMP", ChannelIds.persistenceLog),
                CapturedMessage("Zelda has transitioned from Active Member to Guest.", ChannelIds.moderationLog),
            )
        ),
        VISITOR_TO_VISITOR(
            5uL,
            listOf(),
            null,
            listOf()
        ),
        GUEST_TO_GUEST(
            6uL,
            listOf(RoleIds.guest),
            RoleIds.guest,
            listOf()
        ),
        ACTIVE_MEMBER_TO_ACTIVE_MEMBER(
            7uL,
            listOf(RoleIds.activeMember),
            RoleIds.activeMember,
            listOf()
        ),
        MULTIPLE_ROLES_TO_VISITOR(
            8uL,
            listOf(RoleIds.activeMember, RoleIds.guest), // this could happen if there was a manual misconfiguration of roles
            null,
            listOf(
                CapturedMessage("Role change occurred. 8 transitioned from ${RoleIds.activeMember} to null at MATCH_NOW_TIMESTAMP", ChannelIds.persistenceLog),
                CapturedMessage("Role change occurred. 8 transitioned from ${RoleIds.guest} to null at MATCH_NOW_TIMESTAMP", ChannelIds.persistenceLog),
                CapturedMessage("Zelda has transitioned from Active Member+Guest to Visitor (no role).", ChannelIds.moderationLog),
            )
        );
    }

    private val restClient = mockk<RestClient>()
    private val discordMocker = DiscordMocker(restClient)
    private val database = spyk(Database(discordMocker.mock, activeMemberConfig)).also { dbSpy ->
        // start database with no activity history
        val emptyActivityHistory = ActivityHistory(mapOf(), mapOf(), mapOf())
        coEvery {
            dbSpy._fetchActivityHistory()
        }.returns(Triple(emptyActivityHistory, setOf(), listOf()))

        // don't try to persist empty history, which would add a bunch of empty post history messages to the discordMocker
        coEvery {
            dbSpy._persistMissingPostHistory(emptyActivityHistory, setOf())
        }.just(runs)

        runBlocking {
            dbSpy.initialize(LocalDate.now())
        }
    }
    private val membershipRoleService = MembershipRoleService(discordMocker.mock, activeMemberConfig, database)


    @ParameterizedTest
    @EnumSource(AddAndRemovalTestCase::class)
    fun testAddAndRemoveRoles(case: AddAndRemovalTestCase) = runBlocking {
        setupMocks(case)

        val roleConfig = activeMemberConfig.roleConfigs.single { it.roleId == case.newRoleId }
        membershipRoleService.addOrRemoveMembershipRoleFromUsers(Qualification(roleConfig, null), setOf(case.userId))
        val alreadyHasRole = case.newRoleId == null && case.currentRoleIds.isEmpty() || case.newRoleId in case.currentRoleIds
        if (alreadyHasRole) {
            assertDeletedRoleIdsFromUser(case.userId, listOf())
            assertNoRoleAdded()
        } else {
            assertDeletedRoleIdsFromUser(case.userId, case.currentRoleIds)
            if (case.newRoleId == null) {
                assertNoRoleAdded()
            } else {
                assertAddedRoleId(case.userId, case.newRoleId)
            }
        }


        discordMocker.assertCapturedMessagesEqual(*case.expectedMessages.toTypedArray())
    }


    private fun setupMocks(case: AddAndRemovalTestCase) {
        // Define the parts of the RestClient mockk we will use
        val guildService = mockk<GuildService>()
        val userService = mockk<UserService>()
        every { restClient.guild }.returns(guildService)
        every { restClient.user }.returns(userService)

        // fetch guild member
        coEvery {
            guildService.getGuildMember(Snowflake(guildId), Snowflake(case.userId))
        }.returns(mockk<DiscordGuildMember>().also { user ->
            every { user.roles }.returns(case.currentRoleIds.map { Snowflake(it) })
        })

        // create message is "mocked" through subclassing (above) due to bug in mockk

        // add & remove
        coEvery { guildService.addRoleToGuildMember(any(), any(), any()) }.returns(Unit)
        coEvery { guildService.deleteRoleFromGuildMember(any(), any(), any()) }.returns(Unit)

        // fetch user (username cache)
        coEvery {
            userService.getUser(Snowflake(case.userId))
        }.returns(mockk<DiscordUser>().also { user ->
            every { user.username }.returns(case.username)
        })

        // fetch roles (roleName cache)
        val guildRoles = listOf(
            RoleIds.guest to "Guest",
            RoleIds.activeMember to "Active Member",
        ).map { (roleId, roleName) ->
            mockk<DiscordRole>().also { role ->
                every { role.id }.returns(Snowflake(roleId))
                every { role.name }.returns(roleName)
            }
        }
        coEvery {
            guildService.getGuildRoles(Snowflake(guildId))
        }.returns(guildRoles)
    }

    private fun assertNoRoleAdded() {
        val guildService = restClient.guild
        coVerify(exactly = 0) {
            guildService.addRoleToGuildMember(any(), any(), any())
        }
    }

    private fun assertAddedRoleId(userId: UserId, roleId: RoleId) {
        val guildService = restClient.guild
        coVerify(exactly = 1) {
            guildService.addRoleToGuildMember(Snowflake(guildId), Snowflake(userId), Snowflake(roleId))
        }
    }

    private fun assertDeletedRoleIdsFromUser(userId: UserId, roleIds: List<RoleId>) {
        val guildService = restClient.guild
        coVerify(exactly = roleIds.size) {
            guildService.deleteRoleFromGuildMember(any(), any(), any())
        }
        roleIds.forEach { roleId ->
            coVerify(exactly = 1) {
                guildService.deleteRoleFromGuildMember(Snowflake(guildId), Snowflake(userId), Snowflake(roleId))
            }
        }
    }
}