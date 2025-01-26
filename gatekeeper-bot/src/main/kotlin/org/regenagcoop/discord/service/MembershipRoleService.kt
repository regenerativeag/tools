package org.regenagcoop.discord.service

import mu.KotlinLogging
import org.regenagcoop.Database
import org.regenagcoop.coroutine.parallelForEachIO
import org.regenagcoop.coroutine.parallelMapIO
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.DiscordClient
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.Qualification
import org.regenagcoop.model.config.ActiveMemberConfig
import org.regenagcoop.model.RoleChange
import org.regenagcoop.model.getMembershipRoleIds
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
    suspend fun addOrRemoveMembershipRoleFromUsers(
        qualification: Qualification,
        userIds: Set<UserId>
    ) {
        val roleConfig = qualification.roleConfig
        val roleId = roleConfig.roleId
        val roleName = roleNameCache.lookupOrNoRole(roleId, activeMemberConfig)
        val usernames = userIds.parallelMapIO { usernameCache.lookup(it) }

        userIds.zip(usernames).parallelForEachIO { (userId, username) ->
            val currentMembershipRoleIds = getCurrentMembershipRoleIds(userId)
            if (roleId in currentMembershipRoleIds || roleId == null && currentMembershipRoleIds.isEmpty()) {
                logger.debug { "$username already has roleId=$roleId ($roleName)" }
            } else {
                if (currentMembershipRoleIds.size > 1) {
                    logger.warn("Expected at most one role to remove while adding a role to a user... Removing $currentMembershipRoleIds from $userId")
                }
                discord.users.removeRolesFromUser(userId, currentMembershipRoleIds)
                if (roleId != null) {
                    discord.users.addRoleToUser(userId, roleId)
                }
                handleRoleChanged(userId, currentMembershipRoleIds, qualification)
            }
        }
    }

    private suspend fun getCurrentMembershipRoleIds(userId: UserId): Set<RoleId> {
        return discord.users.getUser(userId).getMembershipRoleIds(activeMemberConfig)
    }

    /** Post messages to appropriate rooms */
    private suspend fun handleRoleChanged(
        userId: UserId,
        previousRoleIds: Collection<RoleId>,
        qualification: Qualification
    ) {
        val newRoleId = qualification.roleConfig.roleId
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

        val roleLevelByRoleId = activeMemberConfig.roleConfigs.mapIndexed { idx, cfg -> cfg.roleId to idx }.toMap()
        val previousRoleLevel = if (previousRoleIds.isEmpty()) {
            roleLevelByRoleId[null]!!
        } else {
            previousRoleIds.maxOf { roleLevelByRoleId[it]!! }
        }
        val newRoleLevel = roleLevelByRoleId[newRoleId]!!

        // Post upgrade, downgrade, and direct messages to appropriate rooms
        val isUpgrade = newRoleLevel > previousRoleLevel
        if (isUpgrade) {
            val welcomeConfig = qualification.path.welcomeMessageConfig
            if (welcomeConfig != null) {
                val channelWelcomeMessage = welcomeConfig.createWelcomeMessage(userId)
                discord.rooms.postMessage(channelWelcomeMessage, welcomeConfig.channel, listOf(userId))
                val directWelcomeMessage = welcomeConfig.directMessageConfig?.message
                if (directWelcomeMessage != null) {
                    discord.users.sendDirectMessageToUser(userId, directWelcomeMessage)
                }
            }
        } else {
            postDowngradeMessage(userId, previousRoleIds, newRoleId)
        }
    }

    private suspend fun postDowngradeMessage(userId: UserId, previousRoleIds: Collection<RoleId>, newRoleId: RoleId?) {
        val username = usernameCache.lookup(userId)
        val newRoleName = newRoleId?.let { roleNameCache.lookupOrNoRole(it, activeMemberConfig) }
        val downgradeConfig = activeMemberConfig.downgradeMessageConfig
        val previousRoleName = concatRolesToString(previousRoleIds)
        val downgradeMessage = downgradeConfig.createDowngradeMessage(activeMemberConfig, username, previousRoleName, newRoleName)
        discord.rooms.postMessage(downgradeMessage, downgradeConfig.channel)
    }

    /**
     * It's possible multiple roles are being removed from the user if there was some manual intervention... or a bug
     * ...If so, join them together into one string
     */
    suspend fun concatRolesToString(roleIds: Collection<RoleId>): String? {
        val membershipRoleIds = roleIds.intersect(activeMemberConfig.membershipRoleIds)

        return if (membershipRoleIds.isEmpty()) {
            null
        } else {
            val roleNames = membershipRoleIds.parallelMapIO { roleNameCache.lookupOrNoRole(it, activeMemberConfig) }
            roleNames.joinToString("+")
        }
    }
}