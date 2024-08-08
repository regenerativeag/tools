package regenerativeag.model

import regenerativeag.discord.model.UserId
import java.time.LocalDate

typealias MutablePostHistory = MutableMap<UserId, MutableSet<LocalDate>>