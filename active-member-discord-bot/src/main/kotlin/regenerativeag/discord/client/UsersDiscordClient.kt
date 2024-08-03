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
        val roleIdxByRoleId = activeMemberConfig.roleConfigs.mapIndexed { idx, cfg ->
            cfg.roleId to idx
        }.toMap()
        runBlocking {
            // TODO: Parallelize requests - https://github.com/regenerativeag/tools/issues/1
            userPairs.forEach { (userId, username) ->
                val userRoles = getGuildMember(userId).roles.map { it.value }.toSet()
                if (roleId in userRoles) {
                    logger.debug { "$username already has role $roleId ($roleName)" }
                } else {
                    val rolesToRemove = activeMemberConfig.roleConfigs.map { it.roleId }.toSet() - roleId

                    // TODO: Parallelize requests - https://github.com/regenerativeag/tools/issues/1
                    rolesToRemove.forEach { roleIdToRemove ->
                        if (roleIdToRemove in userRoles) {
                            removeActiveRole(roleIdToRemove, userId)
                        }
                    }
                    if (rolesToRemove.size > 1) {
                        logger.warn("Expected at most one role to remove while adding a role to a user... Removing $rolesToRemove from $userId")
                    }

                    addRoleToGuildMember(userId, roleId)

                    val currentRoleLevel = userRoles.mapNotNull { roleIdxByRoleId[it] }.maxOrNull()
                    val newRoleLevel = roleIdxByRoleId[roleId]!!
                    val isUpgrade = currentRoleLevel == null || newRoleLevel > currentRoleLevel
                    if (isUpgrade) {
                        val welcomeConfig = roleConfig.welcomeMessageConfig
                        val welcomeMessage = welcomeConfig.createWelcomeMessage(userId)
                        discord.rooms.postMessage(welcomeMessage, welcomeConfig.channel, listOf(userId))
                    } else {
                        val downgradeConfig = activeMemberConfig.downgradeMessageConfig
                        val previousRoleName = concatRolesToString(rolesToRemove)
                        val downgradeMessage = downgradeConfig.createDowngradeMessage(username, previousRoleName, roleName)
                        discord.rooms.postMessage(downgradeMessage, downgradeConfig.channel)
                    }
                }
            }
        }
    }

    fun removeAllActiveRolesFromUser(userId: UserId) {
        val username = usernameCache.lookup(userId)
        runBlocking {
            val userRoles = getGuildMember(userId).roles.map { it.value }.toSet()
            val activeRoles = activeMemberConfig.roleConfigs.map { it.roleId }.toSet()
            val userActiveRoles = userRoles.intersect(activeRoles)
            if (userActiveRoles.isEmpty()) {
                logger.debug { "$username has no active roles to remove" }
            } else {
                if (userActiveRoles.size > 1) {
                    logger.warn("Expected at most one role to remove while removing all active roles from a user... Removing $userActiveRoles from $userId")
                }
                userActiveRoles.forEach { roleId ->
                    removeActiveRole(roleId, userId)
                }
                val removalConfig = activeMemberConfig.removalMessageConfig
                val previousRoleName = concatRolesToString(userActiveRoles)
                val message = removalConfig.createRemovalMessage(username, previousRoleName)
                discord.rooms.postMessage(message, removalConfig.channel)
            }
        }
    }

    /** Remove the role from the user.
     * This function is private, as it likely should not be used directly...
     *  - use [addActiveRole] to transition users between roles
     *  - use [removeAllActiveRolesFromUser] to remove all active roles from a user
     */
    private fun removeActiveRole(roleId: RoleId, userId: UserId) {
        val username = usernameCache.lookup(userId)
        val roleName = roleNameCache.lookup(guildId, roleId)
        runBlocking {
            val userRoles = getGuildMember(userId).roles.map { it.value }
            if (roleId !in userRoles) {
                logger.debug { "$username doesn't have role $roleId ($roleName) to remove" }
            } else {
                deleteRoleFromGuildMember(userId, roleId)
            }
        }
    }

    private suspend fun getGuildMember(userId: UserId) = restClient.guild.getGuildMember(sGuildId, Snowflake(userId))

    private suspend fun getGuildMembers(limit: Int = 100, after: DiscordGuildMember? = null) = restClient.guild.getGuildMembers(sGuildId, limit = limit, after = after?.let { Position.After(it.user.value!!.id) } )

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
            deleteRoleFromGuildMember(userId, roleId)
            restClient.guild.deleteRoleFromGuildMember(sGuildId, Snowflake(userId), Snowflake(roleId))
            logger.info("Removed $roleName from $username")
        }
    }

    /**
     * It's possible multiple roles are being removed from the user if there was some manual intervention... or a bug
     * ...If so, join them together into one string
     */
    private fun concatRolesToString(roleIds: Set<RoleId>): String {
        return roleIds.joinToString("+") { removedRoleId ->
            roleNameCache.lookup(guildId, removedRoleId)
        }
    }
}