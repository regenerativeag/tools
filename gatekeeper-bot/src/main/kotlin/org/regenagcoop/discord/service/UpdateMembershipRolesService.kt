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

        val canActionCauseRoleChange = !isExcludedUserId(userId) && when (triggeringAction) {
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
        val qualification = membershipRoleDeterminationService.determineMembershipRole(today, user, userActivityHistory, triggeringAction)

        val username = usernameCache.lookup(userId)
        if (qualification == null) {
            logger.debug { "User qualified for no role changes due to ${TriggeringAction::class.simpleName}. UserId=$userId ($username)" }
        } else {
            val currentRoleName = membershipRoleService.concatRolesToString(user.membershipRoles)
            val newRoleName = roleNameCache.lookupOrNoRole(qualification.roleConfig.roleId, activeMemberConfig)
            logger.debug { "Updating user's role. User qualified for a role change due to ${TriggeringAction::class.simpleName}. Current Role: $currentRoleName. New Role: $newRoleName. UserId=$userId ($username)" }
            membershipRoleService.addOrRemoveMembershipRoleFromUsers(qualification, setOf(userId))
        }
    }

    suspend fun updateMembershipRolesForAllUsers(today: LocalDate) {
        // 1. fetch activity history for all users from DB
        val activityHistory = database.getActivityHistory()

        // 2. fetch all relevant users
        val allRelevantUsers = usersDiscordClient.getUsersInGuild()
            .filter { !isExcludedUserId(it.userId) }

        // 4. Call roleDeterminationService for relevant users
        val userToQualificationPairs = allRelevantUsers.map { user ->
            val userId = user.userId
            val userActivityHistory = UserActivityHistory(
                userId,
                activityHistory.postHistory[userId]?.toSet() ?: setOf(),
                activityHistory.reactionHistory[userId]?.toSet() ?: setOf(),
                activityHistory.roleChangeHistory[userId]?.toList() ?: listOf()
            )
            val qualification = membershipRoleDeterminationService.determineMembershipRole(today, user, userActivityHistory, null)
            user to qualification
        }

        // 5. Group users by newly determined role
        val usersByQualification = userToQualificationPairs.groupBy { it.second }.mapValues {
            it.value.map { (user, _) -> user }
        }

        // 6. Update role for each group of users in parallel
        usersByQualification.entries.parallelForEachIO { (qualification, users) ->
            val userTriples = users.map { user ->
                val username = usernameCache.lookup(user.userId)
                val oldRoleName = membershipRoleService.concatRolesToString(user.membershipRoles)
                Triple(username, oldRoleName, user.userId)
            }

            if (qualification == null) {
                logger.debug { "Users who qualified for no role changes (Username, Current Role, UserId): $userTriples"}
            } else {
                val newRoleName = roleNameCache.lookupOrNoRole(qualification.roleConfig.roleId, activeMemberConfig)
                logger.debug { "Updating users' roles. New Role: $newRoleName. Users (Username, Old Role, UserId): $userTriples" }
                val userIds = users.map { it.userId }.toSet()
                membershipRoleService.addOrRemoveMembershipRoleFromUsers(qualification, userIds)
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

    private fun isExcludedUserId(userId: UserId) = userId in activeMemberConfig.excludedUserIds
}