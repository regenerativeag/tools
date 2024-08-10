package regenerativeag.discord.client

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import regenerativeag.Database
import regenerativeag.discord.ActiveMemberDiscordBot
import regenerativeag.discord.Discord
import regenerativeag.discord.model.Message
import regenerativeag.discord.model.UserId
import regenerativeag.model.ActiveMemberConfig
import regenerativeag.model.MutablePostHistory
import regenerativeag.model.PostHistory
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
    fun cleanReload() {
        logger.debug { "Reloading" }

        val today = LocalDate.now()
        val postHistory = fetchPostHistory(today)

        logger.debug { "Overwriting post history: $postHistory" }
        database.overwritePostHistory(postHistory)

        updateMemberRolesGivenPostHistory(postHistory, today)

        logger.debug { "Reload complete" }
    }

    fun updateMemberRolesGivenPostHistory(postHistory: PostHistory, today: LocalDate) {
        // Iterate over every role, updating all members in that role
        val computedMembersSets = ActiveMemberDiscordBot.computeActiveMembers(postHistory, today, activeMemberConfig)
        val departedMemberIds = mutableSetOf<UserId>()
        val previousMemberIds = mutableSetOf<UserId>()
        computedMembersSets.zip(activeMemberConfig.roleConfigs).forEach { (members, roleConfig) ->
            val result = updateRoleMembers(members, roleConfig)
            departedMemberIds.addAll(result.departedMemberIds)
            previousMemberIds.addAll(result.previousMemberIds)
        }

        logger.debug { "Members who met a threshold, but left: ${discord.users.mapUserIdsToNames(departedMemberIds)}" }

        val currentMemberIds = mutableSetOf<UserId>()
        computedMembersSets.forEach(currentMemberIds::addAll)

        val inactiveMemberIds = previousMemberIds - currentMemberIds
        logger.debug { "Members who no longer meet a threshold: ${discord.users.mapUserIdsToNames(inactiveMemberIds)}" }
        membershipRoleClient.removeMembershipRolesFromUsers(inactiveMemberIds)
    }


    /** Query dicord for enough recent post history that active member roles can be computed */
    private fun fetchPostHistory(today: LocalDate): PostHistory {
        val daysToLookBack = activeMemberConfig.maxWindowSize
        val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
        val earliestValidDate = today.minusDays(daysToLookBack - 1L)
        runBlocking {
            val channelIds = discord.guild.getChannels()
            logger.debug { "Found channels: ${channelIds.map {discord.channelNameCache.lookup(it)}}" }
            channelIds.forEach { channelId ->
                val messagesInChannel = discord.rooms.readMessagesFromChannelAndSubChannels(earliestValidDate, channelId)
                postHistory.addHistoryFrom(messagesInChannel)
            }
        }
        return postHistory
    }

    /** Ensure that the role identified by [roleConfig] includes exactly the members in [allMembersInRole] */
    private fun updateRoleMembers(allMembersInRole: Set<UserId>, roleConfig: ActiveMemberConfig.RoleConfig): UpdateResult {
        val roleName = discord.roleNameCache.lookup(roleConfig.roleId)
        fun log(prefix: String, userIds: Set<UserId>) {
            logger.debug { "$prefix $roleName (${userIds.size}): ${discord.users.mapUserIdsToNames(userIds).sorted()}" }
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