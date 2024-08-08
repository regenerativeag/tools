package regenerativeag.discord

import dev.kord.rest.request.KtorRequestHandler
import dev.kord.rest.service.RestClient
import io.ktor.client.*
import regenerativeag.discord.client.GuildDiscordClient
import regenerativeag.discord.client.RoomsDiscordClient
import regenerativeag.discord.client.UsersDiscordClient
import regenerativeag.discord.model.GuildId

/**
 * A discord client for a given guild/server.
 */
open class Discord(
    httpClient: HttpClient,
    val guildId: GuildId,
    token: String,
    val dryRun: Boolean,
    val restClient: RestClient = RestClient(KtorRequestHandler(httpClient, token = token)),
) {

    val usernameCache = UsernameCache(restClient)
    val channelNameCache = ChannelNameCache(restClient)
    val roleNameCache = RoleNameCache(restClient)

    val guild = GuildDiscordClient(this)
    val users = UsersDiscordClient(this)
    // This is `open` for tests due to a bug in mockk which prevents us from mocking functions that accept lambdas: https://github.com/mockk/mockk/issues/944
    open val rooms = RoomsDiscordClient(this)
}