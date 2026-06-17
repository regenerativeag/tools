package org.regenagcoop.manual

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.regenagcoop.*
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.client.RoomsDiscordClient
import org.regenagcoop.discord.service.PersistedActivityService
import org.regenagcoop.model.config.ActiveMemberConfig
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
    private val dependencies = Dependencies(configPath = defaultConfigPath, dryRun = true)
    private val activeMemberConfig: ActiveMemberConfig by lazy { dependencies.activeMemberConfig }
    private val discord: Discord by lazy { dependencies.discord }


    @Ignore
    @Test
    fun sendMessage(): Unit = runBlocking {
        // configure
        val userId = UserIds.gatekeeperBot
        val channelId = ChannelIds.moderationLog

        val guestRoleConfig = activeMemberConfig.roleConfigs[0]
        assertEquals(RoleIds.guest, guestRoleConfig.roleId)

        val message = guestRoleConfig.paths.firstNotNullOf { it.welcomeMessageConfig }.createWelcomeMessage(userId)

        // execute
        val roomsDiscordClient = RoomsDiscordClient(discord)
        roomsDiscordClient.postMessage(message, channelId)
    }

    @Ignore
    @Test
    fun readPersistenceChannel(): Unit = runBlocking {
        val persistedActivityService = PersistedActivityService(discord, activeMemberConfig)
        persistedActivityService.fetchPersistedHistoryMessages()
    }
}