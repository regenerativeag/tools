package org.regenagcoop.discord

import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.RestClient
import kotlinx.coroutines.runBlocking
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.GuildId

class RoleNameCache(
    private val restClient: RestClient,
    private val guildId: GuildId,
) {
    private val cache = mutableMapOf<RoleId, String>()
    private val seenGuildIds = mutableSetOf<GuildId>()

    fun lookup(roleId: RoleId): String {
        return synchronized(cache) {
            if (guildId !in seenGuildIds) {
                val roleNameByRoleId = runBlocking {
                    restClient.guild.getGuildRoles(Snowflake(guildId)).associate {
                        it.id.value to it.name
                    }
                }
                cache.putAll(roleNameByRoleId)
                seenGuildIds.add(guildId)
            }
            cache[roleId]!!
        }
    }
}