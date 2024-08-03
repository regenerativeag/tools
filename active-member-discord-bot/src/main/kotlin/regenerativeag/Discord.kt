package regenerativeag

import dev.kord.rest.request.KtorRequestHandler
import dev.kord.rest.service.RestClient
import io.ktor.client.*
import regenerativeag.discord.ChannelNameCache
import regenerativeag.discord.RoleNameCache
import regenerativeag.discord.UsernameCache
import regenerativeag.discord.client.BotDiscordClient
import regenerativeag.discord.client.PostHistoryDiscordClient
import regenerativeag.discord.client.RoomsDiscordClient
import regenerativeag.discord.client.UsersDiscordClient
import regenerativeag.model.ActiveMemberConfig

/** A discord client for a given [ActiveMemberConfig] */
class Discord(
    httpClient: HttpClient,
    val activeMemberConfig: ActiveMemberConfig,
    token: String,
    val dryRun: Boolean,
) {
    internal val restClient = RestClient(KtorRequestHandler(httpClient, token = token))

    val usernameCache = UsernameCache(restClient)
    val channelNameCache = ChannelNameCache(restClient)
    val roleNameCache = RoleNameCache(restClient)

    val postHistory = PostHistoryDiscordClient(this)
    val users = UsersDiscordClient(this)
    val rooms = RoomsDiscordClient(this)
    val bot = BotDiscordClient(this, token)
}