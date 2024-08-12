package org.regenagcoop.manual

import org.junit.jupiter.api.Test
import org.regenagcoop.*
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.RoomsDiscordClient
import kotlin.test.Ignore
import kotlin.test.assertEquals

/**
 * Sometimes, you just want a manual test...
 *
 * To run one of the tests:
 *   - make any edits
 *   - comment out the @Ignore of the test you want to run
 *   - run the test (with dryMode = true) to verify
 *   - set dryMode = false and run the test for real, if needed
 *
 * You can run the test directly through the IDE (if you have one) or command line using junit, if not. TODO: instructions for running on command line.
 */
class ManualTests {
    private val discord = with(DependencyFactory()) {
        Discord(
            createHttpClient(),
            guildId,
            readDiscordApiToken(),
            dryRun = true
        )
    }

    private val roomsDiscordClient = RoomsDiscordClient(discord)

    @Ignore
    @Test
    fun sendMessage(){
        // configure
        val userId = UserIds.larry
        val channelId = ChannelIds.moderationLog

        val guestRoleConfig = activeMemberConfig.roleConfigs[0]
        assertEquals(RoleIds.guest, guestRoleConfig.roleId)

        val message = guestRoleConfig.welcomeMessageConfig.createWelcomeMessage(userId)

        // execute
        roomsDiscordClient.postMessage(message, channelId)
    }
}