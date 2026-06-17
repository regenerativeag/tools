package org.regenagcoop.model.config

import org.regenagcoop.discord.model.ChannelId

data class DowngradeMessageConfig(
    val channel: ChannelId,
    val template: String,
    val usernamePlaceholder: String = "USERNAME",
    val previousRolePlaceholder: String = "PREVIOUS_ROLE",
    val currentRolePlaceholder: String = "CURRENT_ROLE",
) {
    fun createDowngradeMessage(activeMemberConfig: ActiveMemberConfig, username: String, previousRoleName: String?, currentRoleName: String?): String {
        return template
            .replace(usernamePlaceholder, username)
            .replace(previousRolePlaceholder, previousRoleName ?: activeMemberConfig.noRoleName)
            .replace(currentRolePlaceholder, currentRoleName ?: activeMemberConfig.noRoleName)
    }
}