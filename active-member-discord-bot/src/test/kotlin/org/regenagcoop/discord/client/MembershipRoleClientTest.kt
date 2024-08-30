package org.regenagcoop.discord.client

import dev.kord.common.entity.DiscordGuildMember
import dev.kord.common.entity.DiscordRole
import dev.kord.common.entity.DiscordUser
import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.GuildService
import dev.kord.rest.service.RestClient
import dev.kord.rest.service.UserService
import io.ktor.client.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.regenagcoop.discord.client.MembershipRoleClient
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.RoomsDiscordClient
import org.regenagcoop.discord.model.ChannelId
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.ChannelIds
import org.regenagcoop.RoleIds
import org.regenagcoop.activeMemberConfig
import org.regenagcoop.guildId
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class MembershipRoleClientTest {

    data class Message(val text: String, val channelId: ChannelId)

    enum class AddAndRemovalTestCase(
        val userId: UserId,
        val currentRoleIds: List<RoleId>,
        val newRoleId: RoleId?,
        val expectedMessage: Message?,
        val username: String = "Zelda",
    ) {
        VISITOR_TO_GUEST(1uL, listOf(), RoleIds.guest, Message("A warm hello to our newest guest, <@1> :relaxed: Please check out our [Community Guide](https://regenagcoop.org/community-guide/) when you have a moment.", ChannelIds.connect)),
        GUEST_TO_VISITOR(2uL, listOf(RoleIds.guest), null, Message("Zelda has transitioned from Guest to Visitor (no role).", ChannelIds.moderationLog)),

        GUEST_TO_ACTIVE_MEMBER(3uL, listOf(RoleIds.guest), RoleIds.activeMember, Message("Welcome to our community, <@3>!", ChannelIds.community)),
        ACTIVE_MEMBER_TO_GUEST(4uL, listOf(RoleIds.activeMember), RoleIds.guest, Message("Zelda has transitioned from Active Member to Guest.", ChannelIds.moderationLog)),

        VISITOR_TO_VISITOR(5uL, listOf(), null, null),
        GUEST_TO_GUEST(6uL, listOf(RoleIds.guest), RoleIds.guest, null),
        ACTIVE_MEMBER_TO_ACTIVE_MEMBER(7uL, listOf(RoleIds.activeMember), RoleIds.activeMember, null);
    }

    private val capturedMessages = mutableListOf<Message>()
    private val restClient = mockk<RestClient>()
    private val discord = object : Discord(mockk<HttpClient>(), guildId,"test_token", false, restClient) {
        override val rooms = object : RoomsDiscordClient(this) {
            override suspend fun postMessage(message: String, channelId: ChannelId, usersMentioned: List<UserId>) {
                capturedMessages.add(Message(message, channelId))
            }
        }
    }
    private val membershipRoleClient = MembershipRoleClient(discord, activeMemberConfig)


    @ParameterizedTest
    @EnumSource(AddAndRemovalTestCase::class)
    fun testAddAndRemoveRoles(case: AddAndRemovalTestCase) = runBlocking {
        setupMocks(case)

        if (case.newRoleId != null) {
            // add/replace a role
            val roleConfig = activeMemberConfig.roleConfigs.single { it.roleId == case.newRoleId }
            membershipRoleClient.addMembershipRoleToUsers(roleConfig, setOf(case.userId))
            val alreadyHasRole = case.newRoleId in case.currentRoleIds
            if (alreadyHasRole) {
                assertDeletedRoleIdsFromUser(case.userId, listOf())
                assertNoRoleIdAdded(case.userId)
            } else {
                assertDeletedRoleIdsFromUser(case.userId, case.currentRoleIds)
                assertAddedRoleId(case.userId, case.newRoleId)
            }
        } else {
            // remove all roles
            membershipRoleClient.removeMembershipRolesFromUsers(setOf(case.userId))
            assertDeletedRoleIdsFromUser(case.userId, case.currentRoleIds)
            assertNoRoleIdAdded(case.userId)
        }
        assertMessagePosted(case.expectedMessage)
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

    private fun assertNoRoleIdAdded(userId: UserId) {
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

    private fun assertMessagePosted(expectedMessage: Message? = null) {
        val actualMessage = if (expectedMessage != null) {
            capturedMessages.single()
        } else {
            capturedMessages.also { println(it) }
            assertTrue(capturedMessages.isEmpty())
            null
        }
        assertEquals(expectedMessage, actualMessage)
    }
}