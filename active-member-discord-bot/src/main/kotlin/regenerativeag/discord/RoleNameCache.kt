package regenerativeag.discord

import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.RestClient
import kotlinx.coroutines.runBlocking
import regenerativeag.model.RoleId
import regenerativeag.model.GuildId

class RoleNameCache(
    private val restClient: RestClient,
) {
    private val cache = mutableMapOf<RoleId, String>()
    private val seenGuildIds = mutableSetOf<GuildId>()

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