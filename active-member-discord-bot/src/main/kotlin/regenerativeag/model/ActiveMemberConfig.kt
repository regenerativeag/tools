package regenerativeag.model

data class ActiveMemberConfig(
    val guildId: GuildId, // aka "guild id"
    val excludedUserIds: Set<UserId>, // bots
    val roleConfigs: List<RoleConfig>,
    val downgradeMessageConfig: DowngradeMessageConfig,
    val removalMessageConfig: RemovalMessageConfig,
) {
    val maxWindowSize: Int = roleConfigs.flatMap {
            listOf(it.keepRoleConfig.windowSize, it.addRoleConfig.windowSize)
    }.max()

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
        val usernamePlaceholder: String = "USERNAME",
        val previousRolePlaceholder: String = "PREVIOUS_ROLE",
        val currentRolePlaceholder: String = "CURRENT_ROLE",
    ) {
        fun createDowngradeMessage(username: String, previousRoleName: String, currentRoleName: String): String {
            return template
                .replace(usernamePlaceholder, username)
                .replace(previousRolePlaceholder, previousRoleName)
                .replace(currentRolePlaceholder, currentRoleName)
        }
    }

    data class RemovalMessageConfig(
        val channel: ChannelId,
        val template: String,
        val usernamePlaceholder: String = "USERNAME",
        val previousRolePlaceholder: String = "PREVIOUS_ROLE"
    ) {
        fun createRemovalMessage(username: String, previousRoleName: String): String {
            return template
                .replace(usernamePlaceholder, username)
                .replace(previousRolePlaceholder, previousRoleName)
        }
    }
}