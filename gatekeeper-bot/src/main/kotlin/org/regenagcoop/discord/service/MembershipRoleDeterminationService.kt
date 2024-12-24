package org.regenagcoop.discord.service

import org.regenagcoop.model.Qualification
import org.regenagcoop.discord.model.User
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
    private val noRoleRoleConfig = activeMemberConfig.roleConfigs.singleOrNull { it.roleId == null }
        ?: throw IllegalArgumentException("The roleConfig must have a role defined with roleId==null")

    init {
        val allConfigsHaveUniqueRoleId = activeMemberConfig.roleConfigs.toSet().size == activeMemberConfig.roleConfigs.size
        if (!allConfigsHaveUniqueRoleId) {
            throw IllegalArgumentException("The roleConfig may only have one entry per roleId")
        }
    }

    /**
     * Determine which membership role the user should have.
     * Returns null if the user should not have any membership roles
     */
    fun determineMembershipRole(
        today: LocalDate,
        user: User,
        userActivity: UserActivityHistory,
        triggeringAction: TriggeringAction?,
    ): Qualification {
        // check roles in reverse order, so that user is granted the highest role they are qualified for
        for (roleConfig in activeMemberConfig.roleConfigs.reversed()) {
            val qualifyingPath = findQualifyingPath(roleConfig, today, user, userActivity, triggeringAction)

            if (qualifyingPath != null) {
                return Qualification(roleConfig, qualifyingPath)
            }
        }

        return Qualification(noRoleRoleConfig, null)
    }

    private fun findQualifyingPath(
        roleConfig: RoleConfig,
        today: LocalDate,
        user: User,
        userActivity: UserActivityHistory,
        triggeringAction: TriggeringAction?,
    ): Path? {
        for (path in roleConfig.paths) {
            val pathEvaluator = PathEvaluator(path, today, user, userActivity, triggeringAction)
            if (pathEvaluator.evaluate()) {
                return path
            }
        }
        return null
    }

    private class PathEvaluator(
        private val path: Path,
        private val today: LocalDate,
        private val user: User,
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
                    return if (rule.roleId == null) {
                        user.membershipRoles.isEmpty()
                    } else {
                        rule.roleId in user.membershipRoles
                    }
                }
                is Rule.JoinedBefore -> {
                    return user.joinTimestamp < earliestTimestampToConsider
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