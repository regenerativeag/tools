package org.regenagcoop.discord.service

import mu.KotlinLogging
import org.regenagcoop.Database
import org.regenagcoop.coroutine.parallelForEachIO
import org.regenagcoop.coroutine.parallelMapIO
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.config.ActiveMemberConfig
import org.regenagcoop.model.RoleChange
import org.regenagcoop.model.config.RoleConfig
import java.time.Instant

/** Adds/removes roles & posts messages to appropriate rooms */
class MembershipRoleService(
    discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
    private val database: Database,
) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    /**
     * Add an active member role to the users.
     *
     * If the user already has some other active member role, remove that role.
     */
    suspend fun addMembershipRoleToUsers(
        roleConfig: RoleConfig,
        userIds: Set<UserId>
    ) {
        val roleId = roleConfig.roleId
        val roleName = roleNameCache.lookup(roleId)

        val usernames = userIds.parallelMapIO { usernameCache.lookup(it) }

        userIds.zip(usernames).parallelForEachIO { (userId, username) ->
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
                handleRoleChanged(userId, roleIdsToRemove, roleConfig)
            }
        }
    }

    /** Remove all membership roles from the given users */
    suspend fun removeMembershipRolesFromUsers(userIds: Set<UserId>) {
        userIds.parallelForEachIO { inactiveMemberId ->
            val currentMembershipRoleIds = getCurrentMembershipRoleIds(inactiveMemberId)
            if (currentMembershipRoleIds.isNotEmpty()) {
                discord.users.removeRolesFromUser(inactiveMemberId, currentMembershipRoleIds)
                handleRoleChanged(inactiveMemberId, currentMembershipRoleIds, null)
            }
        }
    }

    private suspend fun getCurrentMembershipRoleIds(userId: UserId): Set<RoleId> {
        val currentRoleIds = discord.users.getUserRoles(userId)
        val membershipRoleIds = activeMemberConfig.roleConfigs.map { it.roleId }.toSet()
        return currentRoleIds.intersect(membershipRoleIds)
    }

    /** Post messages to appropriate rooms */
    private suspend fun handleRoleChanged(userId: UserId, previousRoleIds: Collection<RoleId>, newRoleConfig: RoleConfig?) {
        val newRoleId = newRoleConfig?.roleId
        val roleChangeTimestamp = Instant.now()

        // add RoleChange to the database
        // there should always be just one unless someone manually edited roles incorrectly
        val roleChanges = if (previousRoleIds.isEmpty()) {
            listOf(
                RoleChange(userId, null, newRoleId, roleChangeTimestamp)
            )
        } else {
            previousRoleIds.map { previousRoleId ->
                RoleChange(userId, previousRoleId, newRoleId, roleChangeTimestamp)
            }
        }
        roleChanges.forEach { database.addRoleChange(it) }

        val roleIdxByRoleId = activeMemberConfig.roleConfigs.mapIndexed { idx, cfg -> cfg.roleId to idx }.toMap()
        val previousRoleLevel = previousRoleIds.mapNotNull { roleIdxByRoleId[it] }.maxOrNull()
        val newRoleLevel = newRoleId?.let { roleIdxByRoleId[newRoleId]!! }

        // Post upgrade and downgrade messages to appropriate room
        val isUpgrade = newRoleLevel != null && (previousRoleLevel == null || newRoleLevel > previousRoleLevel)
        if (isUpgrade) {
            newRoleConfig!! // non-null due to isUpgrade == true
            val welcomeConfig = newRoleConfig.welcomeMessageConfig
            if (welcomeConfig != null) {
                val welcomeMessage = welcomeConfig.createWelcomeMessage(userId)
                discord.rooms.postMessage(welcomeMessage, welcomeConfig.channel, listOf(userId))
            }
        } else {
            postDowngradeMessage(userId, previousRoleIds, newRoleId)
        }
    }

    private suspend fun postDowngradeMessage(userId: UserId, previousRoleIds: Collection<RoleId>, newRoleId: RoleId?) {
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
    private suspend fun concatRolesToString(roleIds: Collection<RoleId>): String? {
        return if (roleIds.isEmpty()) {
            null
        } else {
            val roleNames = roleIds.parallelMapIO { roleNameCache.lookup(it) }
            roleNames.joinToString("+")
        }
    }
}