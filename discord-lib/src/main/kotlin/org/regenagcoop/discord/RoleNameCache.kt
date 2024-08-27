package org.regenagcoop.discord

import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.RestClient
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.GuildId
import java.util.concurrent.ConcurrentHashMap

class RoleNameCache(
    private val restClient: RestClient,
    private val guildId: GuildId,
) {
    private val cache = ConcurrentHashMap<RoleId, String>()
    private val seenGuildIds = ConcurrentHashMap.newKeySet<GuildId>()

    suspend fun lookup(roleId: RoleId): String {
        if (guildId !in seenGuildIds) {
            val roleNameByRoleId = restClient.guild.getGuildRoles(Snowflake(guildId)).associate {
                it.id.value to it.name
            }
            cache.putAll(roleNameByRoleId)
            seenGuildIds.add(guildId)
        }
        return cache[roleId]!!
    }
}