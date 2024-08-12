package org.regenagcoop

import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.tools.GlobalObjectMapper
import java.io.File

val activeMemberConfig = GlobalObjectMapper.readValue(
    File("bot-config.yml"),
    ActiveMemberConfig::class.java
)

val guildId = activeMemberConfig.guildId

object ChannelIds {
    val connect = 1162017937796911209uL
    val community = 1223262623194153111uL
    val moderationLog = 1230667079213125702uL
}

object RoleIds {
    val guest = 1240396803946582056uL
    val activeMember = 1223026651340996698uL
}

object UserIds {
    val larry = 1221517195931156530uL
    val josh = 1003068122997207060uL
}