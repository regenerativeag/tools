package regenerativeag.discord

import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.RestClient
import kotlinx.coroutines.runBlocking
import regenerativeag.discord.model.RoleId
import regenerativeag.discord.model.GuildId

class RoleNameCache(
    private val restClient: RestClient,
) {
    private val cache = mutableMapOf<RoleId, String>()
    private val seenGuildIds = mutableSetOf<GuildId>()

    // TODO this PR: move guildId to a private val of this cache
    fun lookup(guildId: GuildId, roleId: RoleId): String {
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