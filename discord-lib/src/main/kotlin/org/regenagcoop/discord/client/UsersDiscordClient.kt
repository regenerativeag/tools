package org.regenagcoop.discord.client

import dev.kord.common.entity.DiscordGuildMember
import dev.kord.common.entity.Snowflake
import dev.kord.rest.request.KtorRequestException
import dev.kord.rest.route.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.regenagcoop.coroutine.parallelFilterIO
import org.regenagcoop.coroutine.parallelForEachIO
import org.regenagcoop.coroutine.parallelMapIO
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.UserId

class UsersDiscordClient(discord: Discord) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    suspend fun mapUserIdsToNames(userIds: Iterable<UserId>): List<String> {
        return userIds.parallelMapIO { usernameCache.lookup(it) }
    }

    /** Of the users provided, only return the users which are still in the guild */
    suspend fun filterToUsersCurrentlyInGuild(userIds: Set<UserId>): Set<UserId> {
        return userIds.parallelFilterIO {
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
        }.toSet()
    }

    /** Fetch the users with the given roleId */
    suspend fun getUsersWithRole(roleId: RoleId): Set<UserId> {
        val sRoleId = Snowflake(roleId)
        val limit = 100

        val members = mutableListOf<DiscordGuildMember>()
        do {
            val page = getGuildMembers(limit, members.lastOrNull())
            members.addAll(page)
            page.forEach { usernameCache.cacheFrom(it) }
        } while (page.size == limit)

        return members
            .filter { sRoleId in it.roles }
            .mapNotNull { it.user.value?.id?.value}
            .toSet()
    }

    suspend fun getUserRoles(userId: UserId): Set<RoleId> {
        return getGuildMember(userId).roles.map { it.value }.toSet()
    }

    suspend fun addRoleToUser(userId: UserId, roleId: RoleId) {
        addRoleToGuildMember(userId, roleId)
    }

    /** Remove [roleIds] from [userId]. Returns the roles that were actually removed */
    suspend fun removeRolesFromUser(userId: UserId, roleIds: Collection<RoleId>): Set<RoleId> {
        val currentRoleIds = getUserRoles(userId)
        val roleIdsToRemove = currentRoleIds.intersect(roleIds.toSet())
        deleteRolesFromGuildMember(userId, roleIdsToRemove)
        return roleIdsToRemove
    }

    private suspend fun getGuildMember(userId: UserId) = restClient.guild.getGuildMember(sGuildId, Snowflake(userId))

    private suspend fun getGuildMembers(limit: Int = 100, after: DiscordGuildMember? = null) = restClient.guild.getGuildMembers(sGuildId, limit = limit, after = after?.let { Position.After(it.user.value!!.id) } )

    private suspend fun addRoleToGuildMember(userId: UserId, roleId: RoleId) {
        val username = usernameCache.lookup(userId)
        val roleName = roleNameCache.lookup(roleId)
        if (dryRun) {
            logger.info("Dry run... would have added role=$roleName to $username")
        } else {
            restClient.guild.addRoleToGuildMember(sGuildId, Snowflake(userId), Snowflake(roleId))
            logger.info("Added role $roleName to $username")
        }
    }

    private suspend fun deleteRoleFromGuildMember(userId: UserId, roleId: RoleId) {
        val username = usernameCache.lookup(userId)
        val roleName = roleNameCache.lookup(roleId)
        if (dryRun) {
            logger.info("Dry run... would have removed $roleName from $username")
        } else {
            restClient.guild.deleteRoleFromGuildMember(sGuildId, Snowflake(userId), Snowflake(roleId))
            logger.info("Removed $roleName from $username")
        }
    }

    private suspend fun deleteRolesFromGuildMember(userId: UserId, roleIds: Iterable<RoleId>) {
        roleIds.parallelForEachIO {
            deleteRoleFromGuildMember(userId, it)
        }
    }
}