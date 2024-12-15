package org.regenagcoop

import org.regenagcoop.discord.model.GuildId
import org.regenagcoop.model.config.ActiveMemberConfig
import org.regenagcoop.tools.GlobalObjectMapper
import java.io.File

val defaultConfigPath = "bot-config.yml"

val activeMemberConfig: ActiveMemberConfig by lazy {
    GlobalObjectMapper.readValue(
        File(defaultConfigPath),
        ActiveMemberConfig::class.java
    )
}

val guildId: GuildId by lazy { activeMemberConfig.guildId }

object ChannelIds {
    val connect = 1162017937796911209uL
    val community = 1223262623194153111uL
    val moderationLog = 1230667079213125702uL
    val persistenceLog = 1279607063646965921uL
}

object RoleIds {
    val guest = 1240396803946582056uL
    val activeMember = 1223026651340996698uL
}

object UserIds {
    val gatekeeperBot = 1221517195931156530uL
    val josh = 1003068122997207060uL
}