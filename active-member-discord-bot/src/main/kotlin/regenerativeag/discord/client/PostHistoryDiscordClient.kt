package regenerativeag.discord.client

import dev.kord.rest.service.RestClient
import regenerativeag.Discord
import regenerativeag.discord.PostHistoryFetcher
import regenerativeag.model.PostHistory
import java.time.LocalDate

class PostHistoryDiscordClient(discord: Discord) : DiscordClient(discord){
    /** Query dicord for enough recent post history that active member roles can be computed */
    fun fetch(today: LocalDate): PostHistory {
        val fetcher = PostHistoryFetcher(
            restClient,
            guildId,
            activeMemberConfig.maxWindowSize,
            today,
            channelNameCache
        )
        return fetcher.fetch()
    }
}