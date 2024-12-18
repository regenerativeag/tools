package org.regenagcoop.discord.service

import mu.KotlinLogging
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.model.TriggeringAction
import org.regenagcoop.model.UserActivityHistory
import org.regenagcoop.model.config.ActiveMemberConfig
import org.regenagcoop.model.config.RoleConfig
import org.regenagcoop.model.config.Rule
import java.time.LocalDate
import java.time.ZoneOffset

class MembershipRoleDeterminationService(
    private val activeMemberConfig: ActiveMemberConfig,
) {
    private val logger = KotlinLogging.logger { }

    /**
     * Determine which membership role the user should have.
     * Returns null if the user should not have any membership roles
     */
    fun determineMembershipRole(
        today: LocalDate,
        currentMembershipRoles: Set<RoleId>,
        userActivity: UserActivityHistory,
        triggeringAction: TriggeringAction?,
    ): RoleConfig? {
        // check roles in reverse order, so that user is granted the highest role they are qualified for
        for (roleConfig in activeMemberConfig.roleConfigs.reversed()) {
            val qualified = isUserQualifiedForRole(roleConfig, today, currentMembershipRoles, userActivity, triggeringAction)

            if (qualified) {
                return roleConfig
            }
        }

        return null
    }

    private fun isUserQualifiedForRole(
        roleConfig: RoleConfig,
        today: LocalDate,
        currentMembershipRoles: Set<RoleId>,
        userActivity: UserActivityHistory,
        triggeringAction: TriggeringAction?,
    ): Boolean {
        for (path in roleConfig.paths) {
            val qualified = evaluateRule(path.rule, path.daysToConsider, today, currentMembershipRoles, userActivity, triggeringAction)
            if (qualified) {
                return true
            }
        }
        return false
    }

    private fun evaluateRule(
        rule: Rule,
        daysToConsider: UInt,
        today: LocalDate,
        currentMembershipRoles: Set<RoleId>,
        userActivity: UserActivityHistory,
        triggeringAction: TriggeringAction?
    ): Boolean {
        val earliestDayToConsider = today.minusDays(daysToConsider.toLong() - 1L)
        val earliestTimestampToConsider = earliestDayToConsider.atStartOfDay().toInstant(ZoneOffset.UTC)

        when (rule) {
            is Rule.And -> {
                rule.rules.forEach { subRule ->
                    val result = evaluateRule(subRule, daysToConsider, today, currentMembershipRoles, userActivity, triggeringAction)
                    if (!result) {
                        return false
                    }
                }
                return true
            }
            is Rule.HasRole -> {
                return rule.roleId in currentMembershipRoles
            }
            is Rule.JoinedBefore -> {
                return userActivity.joinedServerTimestamp < earliestTimestampToConsider
            }
            is Rule.NoPostsOrReactions -> {
                val postCount = userActivity.postHistory.count { it >= earliestDayToConsider }
                val reactionCount = userActivity.reactionHistory.count { it >= earliestDayToConsider }
                return postCount == 0 && reactionCount == 0
            }
            is Rule.PreviouslyHadRole -> {
                val qualifyingRoleChange = userActivity.roleChanges.lastOrNull {
                    it.fromRoleId == rule.roleId && it.timestamp >= earliestTimestampToConsider
                }
                return qualifyingRoleChange != null
            }
            is Rule.PostDays -> {
                // TODO #26: continue implementing
                return false
            }
            else -> return false
        }
    }

    /** Determine whether the user should have the role identified by [roleConfig], given the user's [postDays] */
    internal fun _remove_me_____meetsThreshold(roleConfig: RoleConfig, postDays: Set<LocalDate>, today: LocalDate): Boolean {
        // TODO #26: remove this function or refactor

//            val earliestAddDate = today.minusDays(roleConfig.addRoleConfig.windowSize - 1L)
//            val earliestKeepDate = today.minusDays(roleConfig.keepRoleConfig.windowSize - 1L)
//            val meetsAddThreshold = postDays.filter { date -> date >=  earliestAddDate }.size >= roleConfig.addRoleConfig.minPostDays
//            val meetsKeepThreshold = postDays.filter { date -> date >= earliestKeepDate }.size >= roleConfig.keepRoleConfig.minPostDays
//            return meetsAddThreshold && meetsKeepThreshold

        return false
    }
}
