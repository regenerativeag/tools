package regenerativeag.model

import regenerativeag.discord.model.UserId
import java.time.LocalDate

typealias PostHistory = Map<UserId, Set<LocalDate>>