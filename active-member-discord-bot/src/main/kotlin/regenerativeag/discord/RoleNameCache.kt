package regenerativeag.discord

import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.RestClient
import kotlinx.coroutines.runBlocking
import regenerativeag.model.RoleId
import regenerativeag.model.ServerId

class RoleNameCache(
    private val restClient: RestClient,
) {
    private val cache = mutableMapOf<RoleId, String>()
    private val seenServerIds = mutableSetOf<ServerId>()

    fun lookup(serverId: ServerId, roleId: RoleId): String {
        return synchronized(cache) {
            if (serverId !in seenServerIds) {
                val roleNameByRoleId = runBlocking {
                    restClient.guild.getGuildRoles(Snowflake(serverId)).associate {
                        it.id.value to it.name
                    }
                }
                cache.putAll(roleNameByRoleId)
                seenServerIds.add(serverId)
            }
            cache[roleId]!!
        }
    }
}