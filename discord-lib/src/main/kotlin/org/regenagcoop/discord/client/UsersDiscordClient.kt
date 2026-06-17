package org.regenagcoop.discord.client

import dev.kord.common.entity.DiscordGuildMember
import dev.kord.common.entity.Snowflake
import dev.kord.rest.json.request.DMCreateRequest
import dev.kord.rest.route.Position
import mu.KotlinLogging
import org.regenagcoop.coroutine.parallelForEachIO
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.User
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.discord.toUser

class UsersDiscordClient(discord: Discord) : DiscordClient(discord) {
    private val logger = KotlinLogging.logger { }

    suspend fun getUsersInGuild(): List<User> {
        return getGuildMembers().map { it.toUser() }
    }

    suspend fun getUser(userId: UserId): User {
        val discordMember = getGuildMember(userId)
        return discordMember.toUser()
    }

    suspend fun addRoleToUser(userId: UserId, roleId: RoleId) {
        addRoleToGuildMember(userId, roleId)
    }

    /** Remove [roleIds] from [userId]. Returns the roles that were actually removed */
    suspend fun removeRolesFromUser(userId: UserId, roleIds: Collection<RoleId>): Set<RoleId> {
        val currentRoleIds = getUser(userId).roles
        val roleIdsToRemove = currentRoleIds.intersect(roleIds.toSet())
        deleteRolesFromGuildMember(userId, roleIdsToRemove)
        return roleIdsToRemove
    }

    suspend fun sendDirectMessageToUser(userId: UserId, message: String) {
        if (dryRun) {
            val username = usernameCache.lookup(userId)
            logger.debug { "Would have sent a DM directly to $userId ($username):\n$message" }
        } else {
            val sUserId = Snowflake(userId)
            val dmChannel = restClient.user.createDM(DMCreateRequest(sUserId))
            restClient.channel.createMessage(dmChannel.id) {
                this.content = message
            }
        }
    }

    private suspend fun getGuildMembers(limit: Int = 100): List<DiscordGuildMember> {
        val members = mutableListOf<DiscordGuildMember>()
        do {
            val page = getGuildMembers(limit, members.lastOrNull())
            members.addAll(page)
            page.forEach { usernameCache.cacheFrom(it) }
        } while (page.size == limit)

        return members
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