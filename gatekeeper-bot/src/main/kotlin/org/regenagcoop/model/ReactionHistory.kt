package org.regenagcoop.model

import org.regenagcoop.discord.model.UserId
import java.time.LocalDate

typealias ReactionHistory = Map<UserId, Set<LocalDate>>