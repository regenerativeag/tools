package regenerativeag.discord.client

import dev.kord.common.entity.Snowflake
import regenerativeag.Discord
import regenerativeag.model.ActiveMemberConfig

open class DiscordClient(
    protected val discord: Discord,
) {
    protected val activeMemberConfig
        get() = discord.activeMemberConfig
    protected val guildId
        get() = activeMemberConfig.guildId
    protected val sGuildId
        get() = Snowflake(guildId)
    protected val dryRun
        get() = discord.dryRun

    protected val restClient
        get() = discord.restClient
    protected val usernameCache
        get() = discord.usernameCache
    protected val channelNameCache
        get() = discord.channelNameCache
    protected val roleNameCache
        get() = discord.roleNameCache
}