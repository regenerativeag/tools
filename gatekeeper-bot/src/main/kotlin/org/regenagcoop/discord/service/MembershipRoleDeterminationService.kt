package org.regenagcoop.discord.service

import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.model.TriggeringAction
import org.regenagcoop.model.UserActivityHistory
import org.regenagcoop.model.config.ActiveMemberConfig
import org.regenagcoop.model.config.Path
import org.regenagcoop.model.config.RoleConfig
import org.regenagcoop.model.config.Rule
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.WeekFields

class MembershipRoleDeterminationService(
    private val activeMemberConfig: ActiveMemberConfig,
) {

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
            val pathEvaluator = PathEvaluator(path, today, currentMembershipRoles, userActivity, triggeringAction)
            if (pathEvaluator.evaluate()) {
                return true
            }
        }
        return false
    }

    class PathEvaluator(
        private val path: Path,
        private val today: LocalDate,
        private val currentMembershipRoles: Set<RoleId>,
        private val userActivity: UserActivityHistory,
        private val triggeringAction: TriggeringAction?
    ) {
        private val daysToConsider = path.daysToConsider
        private val earliestDayToConsider = today.minusDays(daysToConsider.toLong() - 1L)
        private val earliestTimestampToConsider = earliestDayToConsider.atStartOfDay().toInstant(ZoneOffset.UTC)

        private val qualifyingPostDays by lazy {
            userActivity.postHistory.filter { it >= earliestDayToConsider }
        }

        fun evaluate(): Boolean {
            return evaluateRule(path.rule)
        }

        /** Recursively evaluate the rule */
        private fun evaluateRule(rule: Rule): Boolean {
            when (rule) {
                is Rule.And -> {
                    rule.rules.forEach { subRule ->
                        val result = evaluateRule(subRule)
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
                    val postCount = qualifyingPostDays.size
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
                    val postCount = qualifyingPostDays.size
                    return postCount >= rule.atLeast.toInt()
                }
                is Rule.PostWeeks -> {
                    val weekCount = qualifyingPostDays.map { it.year to it.get(weekOfYearTemporalField) }.toSet().size
                    return weekCount >= rule.atLeast.toInt()
                }
                is Rule.Reacted -> {
                    return if (triggeringAction is TriggeringAction.ReactionAdded) {
                        triggeringAction.emoji == rule.emoji && triggeringAction.messageId == rule.messageId
                    } else {
                        false
                    }
                }
            }
        }
    }

    companion object {
        private val weekOfYearTemporalField = WeekFields.SUNDAY_START.weekOfYear()
    }
}
