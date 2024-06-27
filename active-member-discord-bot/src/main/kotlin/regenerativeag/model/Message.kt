package regenerativeag.model

import java.time.LocalDate

data class Message(
        val userId: UserId,
        val date: LocalDate,
)