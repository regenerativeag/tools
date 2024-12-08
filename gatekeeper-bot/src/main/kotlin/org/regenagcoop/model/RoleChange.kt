package org.regenagcoop.model

import org.regenagcoop.discord.model.RoleId
import org.regenagcoop.discord.model.UserId
import java.time.Instant

data class RoleChange(
    val userId: UserId,
    val fromRoleId: RoleId?,
    val toRoleId: RoleId?,
    val timestamp: Instant,
)