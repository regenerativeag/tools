package org.regenagcoop.model

import org.regenagcoop.discord.model.User
import org.regenagcoop.model.config.ActiveMemberConfig

fun User.getMembershipRoleIds(activeMemberConfig: ActiveMemberConfig) = roles.filter {
    it in activeMemberConfig.membershipRoleIds
}.toSet()