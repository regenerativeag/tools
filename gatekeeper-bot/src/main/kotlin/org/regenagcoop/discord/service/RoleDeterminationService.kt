package org.regenagcoop.discord.service

import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActivityHistory
import org.regenagcoop.model.TriggeringAction
import org.regenagcoop.model.config.ActiveMemberConfig
import org.regenagcoop.model.config.RoleConfig
import java.time.LocalDate

class RoleDeterminationService(
    private val activeMemberConfig: ActiveMemberConfig,
) {
    private val logger = KotlinLogging.logger { }

    /**
     * Determine which membership role the user should have.
     * Returns null if the user should not have any membership roles
     */
    fun determineRole(
        today: LocalDate,
        userId: UserId,
        currentMembershipRoles: Set<RoleId>,
        joinedServerTimestamp: Instant,
        userActivity: ActivityHistory,
        triggeringAction: TriggeringAction?,
    ): RoleConfig? {
        // TODO #26: implement

        // check roles in reverse order, so that user is granted the highest role they are qualified for
        for (roleConfig in activeMemberConfig.roleConfigs.reversed()) {

        }

        return null
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
