package org.regenagcoop.discord.service

import mu.KotlinLogging
import org.regenagcoop.Database
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.MessageId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.TriggeringAction
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

        // TODO #26: implement
        // get user info from discord
        // get user activity history from DB
        // call roleDeterminationService
        // if returned role is null, OR returned roleId == 0, remove all roles from user
        // if returned role is non-null, add role to user.
//        logger.debug { "(Re)adding $roleName for $username (${message.userId})." }
    }

    suspend fun updateMembershipRolesForAllUsers(today: LocalDate) {
        // TODO #26: re-implement

        // 1. fetch activity history for all users from DB

        // 2. fetch all users in all membership roles in parallel

        // 3. All relevant users = all users in activity history + all users in current membership roles

        // 4. Current relevantUsers = filterToUsersCurrentlyInGuild(allRelevantUsers)

        // 4. Call roleDeterminationService for current relevant users

        // 5. Group users by newly determined role

        // 6. Add role to each group of users in parallel, and remove role from those that were determined to have no role.
        //        logger.debug { "(Re)adding $roleName for $username (${message.userId})." }

        // old calls:
//        val retainedUserIdsToAdd = discord.users.filterToUsersCurrentlyInGuild(
//            userIdsToAdd
//        )
//        membershipRoleService.addMembershipRoleToUsers(roleConfig, retainedUserIdsToAdd)
//        val currentMemberIds = discord.users.getUsersWithRole(roleConfig.roleId)
//        val inactiveUsernames = discord.users.mapUserIdsToNames(inactiveMemberIds)
//        membershipRoleService.removeMembershipRolesFromUsers(inactiveMemberIds)
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