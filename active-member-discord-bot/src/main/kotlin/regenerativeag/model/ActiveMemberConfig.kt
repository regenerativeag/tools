package regenerativeag.model

data class ActiveMemberConfig(
        val serverId: ServerId, // aka "guild id"
        val excludedUserIds: Set<UserId>, // bots
        val roleConfigs: List<RoleConfig>,
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
                val prefix: String,
                val suffix: String,
        )
}