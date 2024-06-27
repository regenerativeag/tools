package regenerativeag.discord

import dev.kord.common.entity.DiscordGuildMember
import dev.kord.common.entity.DiscordUser
import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.RestClient
import kotlinx.coroutines.runBlocking
import regenerativeag.model.UserId

class UsernameCache(
    private val restClient: RestClient,
) {
    private val cache = mutableMapOf<UserId, String>()

    fun lookup(userId: UserId): String {
        return synchronized(cache) {
            if (userId !in cache) {
                val username = runBlocking {
                    restClient.user.getUser(Snowflake(userId)).username
                }
                cache[userId] = username
            }
            cache[userId]!!
        }
    }

    fun cacheFrom(user: DiscordUser) {
        synchronized(cache) {
            val userId = user.id.value
            if (userId !in cache) {
                cache[userId] = user.username
            }
        }
    }

    fun cacheFrom(member: DiscordGuildMember) {
        member.user.value?.let { cacheFrom(it)}
    }
}