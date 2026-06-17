package org.regenagcoop.discord

import dev.kord.common.entity.DiscordGuildMember
import kotlinx.datetime.toJavaInstant
import org.regenagcoop.discord.model.User

fun DiscordGuildMember.toUser() = User(
    user.value!!.id.value,
    roles.map { it.value }.toSet(),
    joinedAt.toJavaInstant()
)