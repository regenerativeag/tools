package org.regenagcoop.model

import org.regenagcoop.discord.model.UserId
import java.time.LocalDate

data class UserActivityHistory(
    val userId: UserId,
    val postHistory: Set<LocalDate>,
    val reactionHistory: Set<LocalDate>,
    val roleChanges: List<RoleChange>,
)
