package org.regenagcoop.discord.model

import kotlin.time.Instant
import kotlin.time.toJavaInstant
import java.time.LocalDate
import java.time.ZoneOffset

data class Message(
        val channelId: ChannelId,
        val messageId: MessageId,
        val userId: UserId,
        val instant: Instant,
        val text: String,
) {
        val utcDate: LocalDate
                get() = LocalDate.ofInstant(instant.toJavaInstant(), ZoneOffset.UTC)
}