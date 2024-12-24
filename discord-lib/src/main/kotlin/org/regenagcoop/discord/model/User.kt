package org.regenagcoop.discord.model

import java.time.Instant

data class User(
    val userId: UserId,
    val membershipRoles: Set<RoleId>,
    val joinTimestamp: Instant,
)
