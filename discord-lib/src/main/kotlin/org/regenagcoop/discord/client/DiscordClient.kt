package org.regenagcoop.discord.client

import dev.kord.common.entity.Snowflake
import org.regenagcoop.discord.Discord

open class DiscordClient(
    protected val discord: Discord,
) {
    protected val guildId
        get() = discord.guildId
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