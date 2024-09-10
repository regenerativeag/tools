package org.regenagcoop.model

import org.regenagcoop.discord.model.UserId
import java.time.LocalDate

typealias PostHistory = Map<UserId, Set<LocalDate>>

typealias MutablePostHistory = MutableMap<UserId, MutableSet<LocalDate>>