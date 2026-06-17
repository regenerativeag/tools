package org.regenagcoop.model.config

import org.regenagcoop.discord.model.*
import java.time.LocalDate

data class ActiveMemberConfig(
    val guildId: GuildId, // aka "guild id"
    val excludedUserIds: Set<UserId>, // eg bots
    val roleConfigs: List<RoleConfig>,
    val downgradeMessageConfig: DowngradeMessageConfig,
    val persistenceConfig: PersistenceConfig,
    val noRoleName: String = "NO_ROLE",
    val errorConfig: ErrorConfig,
    val welcomeToGuildMessageConfig: WelcomeMessageConfig,
    val slashCommandConfig: SlashCommandConfig = SlashCommandConfig(),
) {
    private val maxWindowSize: UInt = roleConfigs.flatMap { cfg ->
        cfg.paths.map { path -> path.daysToConsider }
    }.max()

    /**
     * The earliest date we need to consider when scanning for post history.
     * Since we can't scan back for reaction history, this is only used for post history
     */
    fun computeEarliestScanDate(today: LocalDate): LocalDate {
        val daysToLookBack = maxWindowSize
        return if (daysToLookBack == 0u) {
            today
        } else {
            today.minusDays(daysToLookBack.toLong() - 1L)
        }
    }

    val membershipRoleIds = roleConfigs.mapNotNull { it.roleId }.toSet()
}