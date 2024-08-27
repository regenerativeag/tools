package org.regenagcoop.discord.client

import mu.KotlinLogging
import org.regenagcoop.Database
import org.regenagcoop.coroutine.parallelMap
import org.regenagcoop.discord.ActiveMemberDiscordBot
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.MutablePostHistory
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

/** A DiscordClient which posts messages to appropriate rooms when adding/removing roles */
class ResetMembershipsClient(
    discord: Discord,
    private val database: Database,
    private val membershipRoleClient: MembershipRoleClient,
    private val activeMemberConfig: ActiveMemberConfig,
) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    /** Read the message history, correct any issues in the DB, and correct any issues in people's roles */
    suspend fun cleanReload() {
        logger.debug { "Reloading" }

        val today = LocalDate.now()
        val postHistory = fetchPostHistory(today)

        logger.debug { "Overwriting post history: $postHistory" }
        database.overwritePostHistory(postHistory)

        updateMemberRolesGivenPostHistory(postHistory, today)

        logger.debug { "Reload complete" }
    }

    suspend fun updateMemberRolesGivenPostHistory(postHistory: PostHistory, today: LocalDate) {
        // Iterate over every role, updating all members in that role
        val computedMembersSets = ActiveMemberDiscordBot.computeActiveMembers(postHistory, today, activeMemberConfig)
        val departedMemberIds = mutableSetOf<UserId>()
        val previousMemberIds = mutableSetOf<UserId>()
        computedMembersSets.zip(activeMemberConfig.roleConfigs).forEach { (members, roleConfig) ->
            val result = updateRoleMembers(members, roleConfig)
            departedMemberIds.addAll(result.departedMemberIds)
            previousMemberIds.addAll(result.previousMemberIds)
        }

        val departedUsernames = discord.users.mapUserIdsToNames(departedMemberIds)
        logger.debug { "Members who met a threshold, but left: $departedUsernames" }

        val currentMemberIds = mutableSetOf<UserId>()
        computedMembersSets.forEach(currentMemberIds::addAll)

        val inactiveMemberIds = previousMemberIds - currentMemberIds
        val inactiveUsernames = discord.users.mapUserIdsToNames(inactiveMemberIds)
        logger.debug { "Members who no longer meet a threshold: $inactiveUsernames" }
        membershipRoleClient.removeMembershipRolesFromUsers(inactiveMemberIds)
    }


    /** Query dicord for enough recent post history that active member roles can be computed */
    private suspend fun fetchPostHistory(today: LocalDate): PostHistory {
        val daysToLookBack = activeMemberConfig.maxWindowSize
        val earliestValidDate = today.minusDays(daysToLookBack - 1L)

        val channelIds = discord.guild.getChannels()
        val channelNames = channelIds.parallelMap { discord.channelNameCache.lookup(it) }
        logger.debug { "Found channels: $channelNames" }
        val messagesPerChannel = channelIds.parallelMap { channelId ->
            discord.rooms.readMessagesFromChannelAndSubChannels(earliestValidDate, channelId)
        }

        val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
        messagesPerChannel.forEach { postHistory.addHistoryFrom(it) }
        return postHistory
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
        membershipRoleClient.addMembershipRoleToUsers(roleConfig, retainedUserIdsToAdd)

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

    private fun MutablePostHistory.addHistoryFrom(messages: List<Message>) {
        messages.forEach { message ->
            if (message.userId !in this) {
                this[message.userId] = mutableSetOf(message.date)
            } else {
                this[message.userId]!!.add(message.date)
            }
        }
    }
}