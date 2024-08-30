package org.regenagcoop.discord

import dev.kord.common.entity.DiscordGuildMember
import dev.kord.common.entity.DiscordUser
import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.RestClient
import org.regenagcoop.discord.model.UserId
import java.util.concurrent.ConcurrentHashMap

class UsernameCache(
    private val restClient: RestClient,
) {
    private val cache = ConcurrentHashMap<UserId, String>()

    suspend fun lookup(userId: UserId): String {
        if (!cache.containsKey(userId)) {
            val username = restClient.user.getUser(Snowflake(userId)).username
            cache[userId] = username
        }
        return cache[userId]!!
    }

    fun cacheFrom(user: DiscordUser) {
        val userId = user.id.value
        if (!cache.containsKey(userId)) {
            cache[userId] = user.username
        }
    }

    fun cacheFrom(member: DiscordGuildMember) {
        member.user.value?.let { cacheFrom(it)}
    }
}