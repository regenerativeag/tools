package org.regenagcoop.discord.service

import org.regenagcoop.discord.RoleNameCache
import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.model.config.ActiveMemberConfig

suspend fun RoleNameCache.lookupOrNoRole(roleId: RoleId, activeMemberConfig: ActiveMemberConfig) = if (roleId == 0uL) {
    activeMemberConfig.noRoleName
} else {
    lookup(roleId)
}