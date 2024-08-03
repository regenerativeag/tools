package regenerativeag.discord.client

import dev.kord.common.entity.DiscordGuildMember
import dev.kord.common.entity.Snowflake
import dev.kord.rest.request.KtorRequestException
import dev.kord.rest.route.Position
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import regenerativeag.Discord
import regenerativeag.model.ActiveMemberConfig
import regenerativeag.model.RoleId
import regenerativeag.model.UserId

class UsersDiscordClient(discord: Discord) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    fun mapUserIdsToNames(userIds: Iterable<UserId>): List<String> {
        return userIds.map { usernameCache.lookup(it) }
    }

    /** Of the users provided, only return the users which are still in the guild */
    fun filterToUsersCurrentlyInGuild(userIds: Set<UserId>): Set<UserId> {
        return userIds.filter {
            // TODO: Parallelize requests - https://github.com/regenerativeag/tools/issues/1
            runBlocking {
                try {
                    getGuildMember(it)
                    true
                } catch (e: KtorRequestException) {
                    if (e.status.code == 404) {
                        false
                    } else {
                        throw e
                    }
                }
            }
        }.toSet()
    }

    /** Fetch the users with the given roleId */
    fun getUsersWithRole(role: ULong): Set<UserId> {
        val sRole = Snowflake(role)
        val limit = 100
        val members = mutableListOf<DiscordGuildMember>()
        runBlocking {
            do {
                val page = getGuildMembers(limit, members.lastOrNull())
                members.addAll(page)
                page.forEach { usernameCache.cacheFrom(it) }
            } while (page.size == limit)
        }
        return members
            .filter { sRole in it.roles }
            .mapNotNull { it.user.value?.id?.value}
            .toSet()
    }

    /**
     * Add an active member role to a user.
     *
     * If the user already has some other active member role, remove that role.
     */
    fun addActiveRole(roleConfig: ActiveMemberConfig.RoleConfig, userIds: Set<UserId>, ) {
        val userPairs = userIds.map { it to usernameCache.lookup(it) }
        val roleId = roleConfig.roleId
        val roleName = roleNameCache.lookup(guildId, roleId)

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
                    deleteRolesFromGuildMember(userId, roleIdsToRemove)
                    addRoleToGuildMember(userId, roleId)
                    postUpgradeOrDowngradeMessage(userId, roleIdsToRemove, roleConfig)
                }
            }
        }
    }

    fun removeAllActiveRolesFromUser(userId: UserId) {
        val username = usernameCache.lookup(userId)
        runBlocking {
            val currentMembershipRoleIds = getCurrentMembershipRoleIds(userId)
            if (currentMembershipRoleIds.isEmpty()) {
                logger.debug { "$username has no active roles to remove" }
            } else {
                if (currentMembershipRoleIds.size > 1) {
                    logger.warn("Expected at most one role to remove while removing all active roles from a user... Removing $currentMembershipRoleIds from $userId")
                }
                deleteRolesFromGuildMember(userId, currentMembershipRoleIds)
                postRemovalMessage(userId, currentMembershipRoleIds)
            }
        }
    }

    private suspend fun getGuildMember(userId: UserId) = restClient.guild.getGuildMember(sGuildId, Snowflake(userId))

    private suspend fun getGuildMembers(limit: Int = 100, after: DiscordGuildMember? = null) = restClient.guild.getGuildMembers(sGuildId, limit = limit, after = after?.let { Position.After(it.user.value!!.id) } )

    private suspend fun getCurrentMembershipRoleIds(userId: UserId): Set<RoleId> {
        val currentRoleIds = getGuildMember(userId).roles.map { it.value }.toSet()
        val membershipRoleIds = activeMemberConfig.roleConfigs.map { it.roleId }.toSet()
        return currentRoleIds.intersect(membershipRoleIds)
    }

    private suspend fun addRoleToGuildMember(userId: UserId, roleId: RoleId) {
        val username = usernameCache.lookup(userId)
        val roleName = roleNameCache.lookup(guildId, roleId)
        if (dryRun) {
            logger.info("Dry run... would have added role=$roleName to $username")
        } else {
            restClient.guild.addRoleToGuildMember(sGuildId, Snowflake(userId), Snowflake(roleId))
            logger.info("Added role $roleName to $username")
        }
    }

    private suspend fun deleteRoleFromGuildMember(userId: UserId, roleId: RoleId) {
        val username = usernameCache.lookup(userId)
        val roleName = roleNameCache.lookup(guildId, roleId)
        if (dryRun) {
            logger.info("Dry run... would have removed $roleName from $username")
        } else {
            restClient.guild.deleteRoleFromGuildMember(sGuildId, Snowflake(userId), Snowflake(roleId))
            logger.info("Removed $roleName from $username")
        }
    }

    private suspend fun deleteRolesFromGuildMember(userId: UserId, roleIds: Iterable<RoleId>) {
        // TODO: Parallelize requests - https://github.com/regenerativeag/tools/issues/1
        roleIds.forEach { deleteRoleFromGuildMember(userId, it) }
    }

    /**
     * It's possible multiple roles are being removed from the user if there was some manual intervention... or a bug
     * ...If so, join them together into one string
     */
    private fun concatRolesToString(roleIds: Iterable<RoleId>): String {
        return roleIds.joinToString("+") { removedRoleId ->
            roleNameCache.lookup(guildId, removedRoleId)
        }
    }

    private fun postUpgradeOrDowngradeMessage(userId: UserId, previousRoleIds: Iterable<RoleId>, newRoleConfig: ActiveMemberConfig.RoleConfig) {
        val newRoleId = newRoleConfig.roleId

        val roleIdxByRoleId = activeMemberConfig.roleConfigs.mapIndexed { idx, cfg -> cfg.roleId to idx }.toMap()
        val previousRoleLevel = previousRoleIds.mapNotNull { roleIdxByRoleId[it] }.maxOrNull()
        val newRoleLevel = roleIdxByRoleId[newRoleId]!!

        val isUpgrade = previousRoleLevel == null || newRoleLevel > previousRoleLevel
        if (isUpgrade) {
            val welcomeConfig = newRoleConfig.welcomeMessageConfig
            val welcomeMessage = welcomeConfig.createWelcomeMessage(userId)
            discord.rooms.postMessage(welcomeMessage, welcomeConfig.channel, listOf(userId))
        } else {
            val username = usernameCache.lookup(newRoleId)
            val newRoleName = roleNameCache.lookup(guildId, newRoleId)
            val downgradeConfig = activeMemberConfig.downgradeMessageConfig
            val previousRoleName = concatRolesToString(previousRoleIds)
            val downgradeMessage = downgradeConfig.createDowngradeMessage(username, previousRoleName, newRoleName)
            discord.rooms.postMessage(downgradeMessage, downgradeConfig.channel)
        }
    }

    private fun postRemovalMessage(userId: UserId, previousRoleIds: Iterable<RoleId>) {
        val username = usernameCache.lookup(userId)
        val removalConfig = activeMemberConfig.removalMessageConfig
        val previousRoleName = concatRolesToString(previousRoleIds)
        val message = removalConfig.createRemovalMessage(username, previousRoleName)
        discord.rooms.postMessage(message, removalConfig.channel)
    }
}