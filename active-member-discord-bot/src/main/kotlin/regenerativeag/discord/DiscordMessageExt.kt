package regenerativeag.discord

import dev.kord.common.entity.DiscordMessage
import kotlinx.datetime.toJavaInstant
import regenerativeag.model.UserId
import java.time.LocalDate
import java.time.ZoneOffset

fun DiscordMessage.getLocalDate() = LocalDate.ofInstant(this.timestamp.toJavaInstant(), ZoneOffset.UTC)
fun DiscordMessage.getUserId(): UserId = this.author.id.value