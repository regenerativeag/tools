package org.regenagcoop.model

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import org.regenagcoop.discord.model.*
import java.time.LocalDate

data class ActiveMemberConfig(
    val guildId: GuildId, // aka "guild id"
    val excludedUserIds: Set<UserId>, // bots
    val roleConfigs: List<RoleConfig>,
    val downgradeMessageConfig: DowngradeMessageConfig,
    val persistenceConfig: PersistenceConfig,
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

    data class PersistenceConfig(
        val channel: ChannelId,
    )

    data class RoleConfig(
        val roleId: RoleId,
        val paths: List<Path>,
        val welcomeMessageConfig: WelcomeMessageConfig? = null,
    )

    data class Path(
        val daysToConsider: UInt,
        val rule: Rule,
    )

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    sealed class Rule {

        @JsonTypeName("and")
        data class And(
            val rules: List<Rule>
        ): Rule()

        @JsonTypeName("has_role")
        data class HasRole(
            val roleId: RoleId
        ): Rule()

        @JsonTypeName("reacted")
        data class Reacted(
            val emoji: String,
            val messageId: MessageId,
            val removeReaction: Boolean = true,
        ): Rule()
    }


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