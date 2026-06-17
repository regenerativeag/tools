package org.regenagcoop.model.config

import org.regenagcoop.discord.model.ChannelId
import org.regenagcoop.discord.model.UserId

data class WelcomeMessageConfig(
    val channel: ChannelId,
    val template: String,
    val userMentionPlaceholder: String = "USER_MENTION",
    val directMessageConfig: DirectMessageConfig? = null,
) {
    fun createWelcomeMessage(userId: UserId): String {
        return template
            .replace(userMentionPlaceholder, "<@$userId>")
    }
}