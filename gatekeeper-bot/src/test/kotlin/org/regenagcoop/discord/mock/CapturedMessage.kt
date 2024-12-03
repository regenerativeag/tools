package org.regenagcoop.discord.mock

import org.regenagcoop.discord.model.ChannelId

data class CapturedMessage(
        val text: String,
        val channelId: ChannelId,
        val type: Type = Type.CREATE
) {
    enum class Type {
        CREATE,
        EDIT
    }
}
