package org.regenagcoop.discord.service

import mu.KotlinLogging
import org.regenagcoop.coroutine.parallelForEachIO
import org.regenagcoop.discord.ActiveMemberDiscordBot
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.PostHistory
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** A DiscordClient which posts messages to appropriate rooms when adding/removing roles */
class ResetMembershipsService(
    discord: Discord,
    private val membershipRoleService: MembershipRoleService,
    private val activeMemberConfig: ActiveMemberConfig,
) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    suspend fun resetRolesGivenPostHistory(postHistory: PostHistory, today: LocalDate) {
        // 1. Given the postHistory, compute what members should be in what roles

        // computedMemberSets is a list
        // - in the same order as activeMemberConfig.roleConfigs
        // - of which members are in what roles... given the post history, today's date, and the roleConfigs
        val computedMembersSets = ActiveMemberDiscordBot.computeActiveMembers(postHistory, today, activeMemberConfig)
        val departedMemberIds = ConcurrentHashMap.newKeySet<UserId>()
        val previousMemberIds = ConcurrentHashMap.newKeySet<UserId>()

        // 2. Iterate over every role, resetting all members in that role
        computedMembersSets.zip(activeMemberConfig.roleConfigs).parallelForEachIO { (members, roleConfig) ->
            val result = updateRoleMembers(members, roleConfig)
            departedMemberIds.addAll(result.departedMemberIds)
            previousMemberIds.addAll(result.previousMemberIds)
        }

        val departedUsernames = discord.users.mapUserIdsToNames(departedMemberIds)
        logger.debug { "Members who met a threshold, but left: $departedUsernames" }

        val currentMemberIds = mutableSetOf<UserId>()
        computedMembersSets.forEach(currentMemberIds::addAll)

        // And finally, remove all roles from members who don't meet any thresholds
        val inactiveMemberIds = previousMemberIds - currentMemberIds
        val inactiveUsernames = discord.users.mapUserIdsToNames(inactiveMemberIds)
        logger.debug { "Members who no longer meet a threshold: $inactiveUsernames" }
        membershipRoleService.removeMembershipRolesFromUsers(inactiveMemberIds)
    }


    /** Ensure that the role identified by [roleConfig] includes exactly the members in [allMembersInRole] */
    private suspend fun updateRoleMembers(allMembersInRole: Set<UserId>, roleConfig: ActiveMemberConfig.RoleConfig): UpdateResult {
        val roleName = discord.roleNameCache.lookup(roleConfig.roleId)
        suspend fun log(prefix: String, userIds: Set<UserId>) {
            val usernames = discord.users.mapUserIdsToNames(userIds).sorted()
            logger.debug { "$prefix $roleName (${userIds.size}): $usernames" }
        }

        log("Computed members in", allMembersInRole)

        val currentMemberIds = discord.users.getUsersWithRole(roleConfig.roleId)
        log("Current members in", currentMemberIds)

        val userIdsToAdd = allMembersInRole - currentMemberIds
        log("New members to add to", userIdsToAdd)
        val retainedUserIdsToAdd = discord.users.filterToUsersCurrentlyInGuild(
            userIdsToAdd
        )
        membershipRoleService.addMembershipRoleToUsers(roleConfig, retainedUserIdsToAdd)

        return UpdateResult(
            previousMemberIds = currentMemberIds,
            departedMemberIds = userIdsToAdd - retainedUserIdsToAdd,
        )
    }

    private data class UpdateResult(
        /** The users who were in the role before this function ran */
        val previousMemberIds: Set<UserId>,
        /** The users who qualified for the role, but left the server */
        val departedMemberIds: Set<UserId>,
    )
}