package org.regenagcoop.model.config

import org.regenagcoop.discord.model.ChannelId

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