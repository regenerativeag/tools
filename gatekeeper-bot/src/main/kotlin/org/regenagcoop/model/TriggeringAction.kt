package org.regenagcoop.model

import org.regenagcoop.discord.model.MessageId

/** A user action which may cause an effect */
sealed class TriggeringAction {
    data class PostAdded(
        val isFirstPostOfDay: Boolean,
    ): TriggeringAction()

    data class ReactionAdded(
        val messageId: MessageId,
        val emoji: String,
    ): TriggeringAction()
}