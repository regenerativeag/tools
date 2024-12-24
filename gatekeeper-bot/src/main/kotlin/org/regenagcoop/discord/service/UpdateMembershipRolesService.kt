package org.regenagcoop.discord.service

import mu.KotlinLogging
import org.regenagcoop.Database
import org.regenagcoop.coroutine.parallelForEachIO
import org.regenagcoop.coroutine.parallelMapIO
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.client.UsersDiscordClient
import org.regenagcoop.discord.model.MessageId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.TriggeringAction
import org.regenagcoop.model.UserActivityHistory
import org.regenagcoop.model.config.ActiveMemberConfig
import org.regenagcoop.model.config.Rule
import java.time.LocalDate

/** Determines and grants roles to users */
class UpdateMembershipRolesService(
    discord: Discord,
    private val membershipRoleService: MembershipRoleService,
    private val activeMemberConfig: ActiveMemberConfig,
    private val database: Database,
) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    private val membershipRoleDeterminationService = MembershipRoleDeterminationService(activeMemberConfig)
    private val usersDiscordClient = UsersDiscordClient(discord)

    private val relevantReactionMessageIdEmojiPairs: Set<Pair<MessageId, String>> =
        activeMemberConfig.roleConfigs.flatMap { roleConfig ->
            roleConfig.paths.flatMap { path -> getReactionMessageIdEmojiPairs(path.rule) }
        }.toSet()

    suspend fun updateMembershipRoleForUser(
        userId: UserId,
        today: LocalDate,
        triggeringAction: TriggeringAction
    ) {
        val canActionCauseRoleChange = when (triggeringAction) {
            is TriggeringAction.PostAdded -> triggeringAction.isFirstPostOfDay
            is TriggeringAction.ReactionAdded -> {
                val messageIdEmojiPair = triggeringAction.messageId to triggeringAction.emoji
                messageIdEmojiPair in relevantReactionMessageIdEmojiPairs
            }
        }

        if (!canActionCauseRoleChange) {
            return
        }

        val user = usersDiscordClient.getUser(userId)
        val userActivityHistory = database.getUserActivityHistory(userId)
        val roleConfig = membershipRoleDeterminationService.determineMembershipRole(today, user, userActivityHistory, triggeringAction)

        val username = usernameCache.lookup(userId)
        if (roleConfig == null) {
            logger.debug { "User qualified for no roles. Removing roles from $username ($userId)."}
            membershipRoleService.removeMembershipRolesFromUsers(setOf(userId))
        } else {
            val roleName = roleNameCache.lookupOrNoRole(roleConfig.roleId, activeMemberConfig)
            logger.debug { "User qualified for role. (Re)adding $roleName for $username ($userId)." }
            membershipRoleService.addMembershipRoleToUsers(roleConfig, setOf(userId))
        }
    }

    suspend fun updateMembershipRolesForAllUsers(today: LocalDate) {
        // 1. fetch activity history for all users from DB
        val activityHistory = database.getActivityHistory()

        // 2. fetch all users in all membership roles in parallel
        val currentUserIdsByRoleId = activeMemberConfig.roleConfigs.parallelMapIO { roleConfig ->
            roleConfig.roleId to usersDiscordClient.getUsersWithRole(roleConfig.roleId)
        }.toMap()

        val allRelevantUserIds = usersDiscordClient.filterToUsersCurrentlyInGuild(
            activityHistory.postHistory.keys +
                activityHistory.reactionHistory.keys +
                activityHistory.roleChangeHistory.keys +
                currentUserIdsByRoleId.values.flatten().toSet()
        )


        // TODO #26: optimization - if we cache the joinDate, we won't have to fetch every single user twice
        val allRelevantUsers = allRelevantUserIds.parallelMapIO(usersDiscordClient::getUser)

        // 4. Call roleDeterminationService for current relevant users
        val userToRoleConfigPairs = allRelevantUsers.map { user ->
            val userId = user.userId
            val userActivityHistory = UserActivityHistory(
                userId,
                activityHistory.postHistory[userId]?.toSet() ?: setOf(),
                activityHistory.reactionHistory[userId]?.toSet() ?: setOf(),
                activityHistory.roleChangeHistory[userId]?.toList() ?: listOf()
            )
            val roleConfig = membershipRoleDeterminationService.determineMembershipRole(today, user, userActivityHistory, null)
            user to roleConfig
        }

        // 5. Group users by newly determined role
        // TODO #26: test that grouping by null key works as expected here and below
        val userIdsByRoleConfig = userToRoleConfigPairs.groupBy { it.second }.mapValues {
            it.value.map { (user, _) -> user.userId }.toSet()
        }

        // 6. Add role to each group of users in parallel, and remove role from those that were determined to have no role.
        userIdsByRoleConfig.entries.parallelForEachIO { (roleConfig, userIds) ->
            if (roleConfig == null) {
                logger.debug { "Removing roles from users who qualified for no roles: $userIds"}
                membershipRoleService.removeMembershipRolesFromUsers(userIds)
            } else {
                // TODO #26: we need the path the user qualified and the roleConfig, so we can have different messaging per path
                val roleName = roleNameCache.lookupOrNoRole(roleConfig.roleId, activeMemberConfig)
                val usernames = userIds.map { usernameCache.lookup(it) }.sorted()
                logger.debug { "(Re)adding $roleName to: $usernames"}
                membershipRoleService.addMembershipRoleToUsers(roleConfig, userIds)
            }
        }
    }

    /** Recursively walk the rule, adding all (messageId, emoji) pairs from all Reaction rules to the result */
    private fun getReactionMessageIdEmojiPairs(rule: Rule): List<Pair<MessageId, String>> {
        return when (rule) {
            is Rule.Reacted -> listOf(rule.messageId to rule.emoji)
            is Rule.And -> rule.rules.flatMap(::getReactionMessageIdEmojiPairs)
            else -> listOf()
        }
    }
}