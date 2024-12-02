package org.regenagcoop.discord

import dev.kord.common.entity.DiscordMessage
import kotlinx.datetime.toJavaInstant
import org.regenagcoop.discord.model.ChannelId
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.MessageId
import org.regenagcoop.discord.model.UserId
import java.time.LocalDate
import java.time.ZoneOffset

fun DiscordMessage.getUtcDate() = LocalDate.ofInstant(this.timestamp.toJavaInstant(), ZoneOffset.UTC)
fun DiscordMessage.getUserId(): UserId = this.author.id.value
fun DiscordMessage.getChannelId(): ChannelId = this.channelId.value
fun DiscordMessage.getMessageId(): MessageId = this.id.value
fun DiscordMessage.toMessage() = Message(getChannelId(), getMessageId(), getUserId(), this.timestamp, this.content)