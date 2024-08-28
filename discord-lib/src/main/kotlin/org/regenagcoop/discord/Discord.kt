package org.regenagcoop.discord

import dev.kord.common.annotation.KordUnsafe
import dev.kord.rest.ratelimit.ParallelRequestRateLimiter
import dev.kord.rest.request.KtorRequestHandler
import dev.kord.rest.service.RestClient
import io.ktor.client.*
import org.regenagcoop.discord.client.GuildDiscordClient
import org.regenagcoop.discord.client.RoomsDiscordClient
import org.regenagcoop.discord.client.UsersDiscordClient
import org.regenagcoop.discord.model.GuildId

/**
 * A discord client for a given guild/server.
 */
open class Discord @OptIn(KordUnsafe::class) constructor(
    httpClient: HttpClient,
    val guildId: GuildId,
    token: String,
    val dryRun: Boolean,
    val restClient: RestClient = RestClient(
        KtorRequestHandler(
            httpClient,
            // TODO #13: Consider a different RateLimiter - https://github.com/regenerativeag/tools/issues/13
            requestRateLimiter = ParallelRequestRateLimiter(),
            token = token
        )
    ),
) {

    val usernameCache = UsernameCache(restClient)
    val channelNameCache = ChannelNameCache(restClient)
    val roleNameCache = RoleNameCache(restClient, guildId)

    val guild = GuildDiscordClient(this)
    val users = UsersDiscordClient(this)
    // This is `open` for tests due to a bug in mockk which prevents us from mocking functions that accept lambdas: https://github.com/mockk/mockk/issues/944
    open val rooms = RoomsDiscordClient(this)
}