package regenerativeag.discord.client

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import regenerativeag.discord.Discord
import regenerativeag.discord.model.RoleId
import regenerativeag.discord.model.UserId
import regenerativeag.model.ActiveMemberConfig

/** A DiscordClient which posts messages to appropriate rooms when adding/removing roles */
class MembershipRoleClient(
    discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    /**
     * Add an active member role to the users.
     *
     * If the user already has some other active member role, remove that role.
     */
    fun addMembershipRoleToUsers(roleConfig: ActiveMemberConfig.RoleConfig, userIds: Set<UserId>) {
        val roleId = roleConfig.roleId
        val userPairs = userIds.map { it to usernameCache.lookup(it) }
        val roleName = roleNameCache.lookup(roleId)

        runBlocking {
            // TODO: Parallelize requests - https://github.com/regenerativeag/tools/issues/1
            userPairs.forEach { (userId, username) ->
                val currentMembershipRoleIds = getCurrentMembershipRoleIds(userId)
                if (roleId in currentMembershipRoleIds) {
                    logger.debug { "$username already has role $roleId ($roleName)" }
                } else {
                    val roleIdsToRemove = currentMembershipRoleIds - roleId
                    if (roleIdsToRemove.size > 1) {
                        logger.warn("Expected at most one role to remove while adding a role to a user... Removing $roleIdsToRemove from $userId")
                    }
                    discord.users.removeRolesFromUser(userId, roleIdsToRemove)
                    discord.users.addRoleToUser(userId, roleId)
                    postUpgradeOrDowngradeMessage(userId, roleIdsToRemove, roleConfig)
                }
            }
        }
    }

    /** Remove all membership roles from the given users */
    fun removeMembershipRolesFromUsers(userIds: Set<UserId>) {
        runBlocking {
            userIds.forEach { inactiveMemberId ->
                val currentMembershipRoleIds = getCurrentMembershipRoleIds(inactiveMemberId)
                if (currentMembershipRoleIds.isNotEmpty()) {
                    discord.users.removeRolesFromUser(inactiveMemberId, currentMembershipRoleIds)
                    postUpgradeOrDowngradeMessage(inactiveMemberId, currentMembershipRoleIds, null)
                }
            }
        }
    }

    private fun getCurrentMembershipRoleIds(userId: UserId): Set<RoleId> {
        val currentRoleIds = discord.users.getUserRoles(userId)
        val membershipRoleIds = activeMemberConfig.roleConfigs.map { it.roleId }.toSet()
        return currentRoleIds.intersect(membershipRoleIds)
    }

    private fun postUpgradeOrDowngradeMessage(userId: UserId, previousRoleIds: Collection<RoleId>, newRoleConfig: ActiveMemberConfig.RoleConfig?) {
        val newRoleId = newRoleConfig?.roleId

        val roleIdxByRoleId = activeMemberConfig.roleConfigs.mapIndexed { idx, cfg -> cfg.roleId to idx }.toMap()
        val previousRoleLevel = previousRoleIds.mapNotNull { roleIdxByRoleId[it] }.maxOrNull()
        val newRoleLevel = newRoleId?.let { roleIdxByRoleId[newRoleId]!! }

        val isUpgrade = newRoleLevel != null && (previousRoleLevel == null || newRoleLevel > previousRoleLevel)
        if (isUpgrade) {
            newRoleConfig!! // non-null due to isUpgrade == true
            val welcomeConfig = newRoleConfig.welcomeMessageConfig
            val welcomeMessage = welcomeConfig.createWelcomeMessage(userId)
            discord.rooms.postMessage(welcomeMessage, welcomeConfig.channel, listOf(userId))
        } else {
            postDowngradeMessage(userId, previousRoleIds, newRoleId)
        }
    }

    private fun postDowngradeMessage(userId: UserId, previousRoleIds: Collection<RoleId>, newRoleId: RoleId?) {
        val username = usernameCache.lookup(userId)
        val newRoleName = newRoleId?.let { roleNameCache.lookup(newRoleId) }
        val downgradeConfig = activeMemberConfig.downgradeMessageConfig
        val previousRoleName = concatRolesToString(previousRoleIds)
        val downgradeMessage = downgradeConfig.createDowngradeMessage(username, previousRoleName, newRoleName)
        discord.rooms.postMessage(downgradeMessage, downgradeConfig.channel)
    }


    /**
     * It's possible multiple roles are being removed from the user if there was some manual intervention... or a bug
     * ...If so, join them together into one string
     */
    private fun concatRolesToString(roleIds: Collection<RoleId>): String? {
        return if (roleIds.isEmpty()) {
            null
        } else {
            roleIds.joinToString("+") { removedRoleId ->
                roleNameCache.lookup(removedRoleId)
            }
        }
    }
}