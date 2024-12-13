package org.regenagcoop.discord.model

import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import java.time.LocalDate
import java.time.ZoneOffset

data class Reaction(
    val userId: UserId,
    val instant: Instant
) {
    val utcDate: LocalDate
        get() = LocalDate.ofInstant(instant.toJavaInstant(), ZoneOffset.UTC)
}