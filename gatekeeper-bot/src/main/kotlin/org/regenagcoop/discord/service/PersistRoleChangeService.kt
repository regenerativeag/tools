package org.regenagcoop.discord.service

import org.regenagcoop.discord.Discord
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.RoleChange

class PersistRoleChangeService(
    private val discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
) {
    suspend fun persistRoleChange(roleChange: RoleChange) {
        val message = with(roleChange) {
            "Role change occurred. $userId transitioned from $fromRoleId to $toRoleId at $timestamp"
        }
        discord.rooms.postMessage(message, activeMemberConfig.persistenceConfig.channel)
    }
}