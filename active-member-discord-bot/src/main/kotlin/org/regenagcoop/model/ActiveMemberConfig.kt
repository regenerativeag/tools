package org.regenagcoop.model

import org.regenagcoop.discord.model.ChannelId
import org.regenagcoop.discord.model.GuildId
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.UserId
import java.time.LocalDate

data class ActiveMemberConfig(
    val guildId: GuildId, // aka "guild id"
    val excludedUserIds: Set<UserId>, // bots
    val roleConfigs: List<RoleConfig>,
    val downgradeMessageConfig: DowngradeMessageConfig,
) {
    private val maxWindowSize: Int = roleConfigs.flatMap {
            listOf(it.keepRoleConfig.windowSize, it.addRoleConfig.windowSize)
    }.max()

    /**
     * The earliest date we need to consider when scanning for post history.
     * Since we can't scan back for reaction history, this is only used for post history
     */
    fun computeEarliestScanDate(today: LocalDate): LocalDate {
        val daysToLookBack = maxWindowSize
        return today.minusDays(daysToLookBack - 1L)
    }

    data class RoleConfig(
        val roleId: RoleId,
        val addRoleConfig: AddRoleConfig,
        val keepRoleConfig: KeepRoleConfig,
        val welcomeMessageConfig: WelcomeMessageConfig,
    )

    data class WelcomeMessageConfig(
        val channel: ChannelId,
        val template: String,
        val userMentionPlaceholder: String = "USER_MENTION",
    ) {
        fun createWelcomeMessage(userId: UserId): String {
            return template
                .replace(userMentionPlaceholder, "<@$userId>")
        }
    }

    data class DowngradeMessageConfig(
        val channel: ChannelId,
        val template: String,
        val noRoleName: String = "(no role)",
        val usernamePlaceholder: String = "USERNAME",
        val previousRolePlaceholder: String = "PREVIOUS_ROLE",
        val currentRolePlaceholder: String = "CURRENT_ROLE",
    ) {
        fun createDowngradeMessage(username: String, previousRoleName: String?, currentRoleName: String?): String {
            return template
                .replace(usernamePlaceholder, username)
                .replace(previousRolePlaceholder, previousRoleName ?: noRoleName)
                .replace(currentRolePlaceholder, currentRoleName ?: noRoleName)
        }
    }
}