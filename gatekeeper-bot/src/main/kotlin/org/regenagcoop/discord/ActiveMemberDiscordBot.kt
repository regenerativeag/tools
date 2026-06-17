package org.regenagcoop.discord

import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.regenagcoop.Database
import org.regenagcoop.coroutine.TopLevelJob.Companion.awaitEndlessJobs
import org.regenagcoop.coroutine.TopLevelJob.Companion.createTopLevelJob
import org.regenagcoop.model.config.ActiveMemberConfig
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.Reaction
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.discord.service.*
import org.regenagcoop.model.TriggeringAction
import java.lang.Exception
import java.time.*
import java.time.temporal.ChronoUnit

class ActiveMemberDiscordBot(
    httpClient: HttpClient,
    discordApiToken: String,
    dryRun: Boolean,
    private val database: Database,
    private val activeMemberConfig: ActiveMemberConfig,
) {
    private val logger = KotlinLogging.logger {  }

    private val canUpdateRolesOrDbMutex = Mutex()
    private val discord = Discord(httpClient, activeMemberConfig.guildId, discordApiToken, dryRun)
    private val membershipRoleService = MembershipRoleService(discord, activeMemberConfig, database)
    private val updateMembershipRolesService = UpdateMembershipRolesService(discord, membershipRoleService, activeMemberConfig, database)

    private val slashCommandService = SlashCommandService(discord, discord.rooms, activeMemberConfig)
    private val bot = DiscordBot(
        discord,
        discordApiToken,
        onTopLevelError = ::onTopLevelError,
        onJoinedGuild = ::onJoinedGuild,
        onMessage = ::onMessage,
        onReaction = ::onReaction,
        getSlashCommands = slashCommandService::getCommandDefinitions,
        onSlashCommand = slashCommandService::handleSlashCommand,
    )


    fun start() {
        val startupDate = getTodaysDate()

        // Load the in-memory database by fetching from Discord & persist any post data that was missing in the persistence channel
        val loadDatabaseJob = createTopLevelJob(
            name = "load database"
        ) {
            canUpdateRolesOrDbMutex.withLock {
                logger.debug { "Initializing database" }
                database.initialize(startupDate)
            }
        }

        // Compute roles from database & reset roles for all users
        val resetRolesJob = createTopLevelJob(
            name = "reset roles",
            dependencies = listOf(loadDatabaseJob)
        ){
            logger.debug { "Resetting roles" }
            updateMembershipRolesService.updateMembershipRolesForAllUsers(startupDate)
        }

        // ENDLESSLY listen for websocket events from discord.
        val listenForDiscordEventsJob = createTopLevelJob(
            name = "listen for events",
            dependencies = listOf(loadDatabaseJob, resetRolesJob)
        ) {
            logger.debug { "Listening for discord events" }
            bot.login() // endlessly listen for websocket events from discord
        }

        // ENDLESSLY do daily tasks
        val dailyJob = createTopLevelJob(
            name = "daily tasks",
            dependencies = listOf(loadDatabaseJob, resetRolesJob)
        ) {
            while (true) {
                // Pause this coroutine until 12:05 am UTC
                val delayMs = millisUntilNextDailyJobExecution()
                val nextExecutionLocalTime = LocalDateTime.now().plus(delayMs, ChronoUnit.MILLIS)
                logger.debug { "Next execution of daily job scheduled for $nextExecutionLocalTime" }
                delay(delayMs)

                logger.debug { "Daily Job started" }

                logger.debug { "Downgrading roles for users who no longer meet threshold" }
                downgradeRoles()

                val yesterday = getTodaysDate().minusDays(1)
                logger.debug { "Persisting yesterday's ($yesterday) post history" }
                database.persistYesterdaysPostHistory(yesterday)
            }
        }

        // In the main thread, block until all jobs are complete
        // Some jobs are endless, so the only way to exit this program is for the user to press Ctl+C or Cmd+C or kill the process.
        awaitEndlessJobs(listenForDiscordEventsJob, dailyJob)
    }

    private suspend fun onTopLevelError(exception: Exception) {
        logger.error(exception) { "Top level error occurred" }
        val message = "Top level error occurred! Stack trace:\n\n${exception.stackTraceToString()}"
        discord.rooms.postMessage(message, activeMemberConfig.errorConfig.channel)
    }

    /** If this message results in the user meeting an active-member threshold, adjust the user's roles. */
    private suspend fun onMessage(message: Message) {
        canUpdateRolesOrDbMutex.withLock {
            val isFirstPostOfDay = database.addPost(message.userId, message.utcDate)
            val triggeringAction = TriggeringAction.PostAdded(isFirstPostOfDay)
            updateMembershipRolesService.updateMembershipRoleForUser(message.userId, message.utcDate, triggeringAction)
        }
    }

    /** Update the reaction history in the database & persist reaction in persistence channel */
    private suspend fun onReaction(reaction: Reaction) {
        canUpdateRolesOrDbMutex.withLock {
            database.addReaction(reaction.userId, reaction.utcDate)
            val triggeringAction = TriggeringAction.ReactionAdded(reaction.messageId, reaction.emoji)
            updateMembershipRolesService.updateMembershipRoleForUser(reaction.userId, reaction.utcDate, triggeringAction)
        }
    }

    private suspend fun onJoinedGuild(userId: UserId) {
        val username = discord.usernameCache.lookup(userId)
        logger.debug { "$username ($userId) joined the guild!" }
        val welcomeConfig = activeMemberConfig.welcomeToGuildMessageConfig
        val channelWelcomeMessage = welcomeConfig.createWelcomeMessage(userId)
        discord.rooms.postMessage(channelWelcomeMessage, welcomeConfig.channel, listOf(userId))
        val directWelcomeMessage = welcomeConfig.directMessageConfig?.message
        if (directWelcomeMessage != null) {
            discord.users.sendDirectMessageToUser(userId, directWelcomeMessage)
        }
    }

    /** Millis until 12:05am UTC */
    private fun millisUntilNextDailyJobExecution(): Long {
        val nowUTC = ZonedDateTime.now(ZoneOffset.UTC)

        // 12:05 am
        val todayRunTimeUTC = ZonedDateTime.of(nowUTC.year, nowUTC.monthValue, nowUTC.dayOfMonth, 0, 5, 0, 0, nowUTC.zone)

        val todayRunTimeAlreadyPassed = todayRunTimeUTC < nowUTC
        val nextRunTimeUTC = if (todayRunTimeAlreadyPassed) {
            todayRunTimeUTC.plusDays(1)
        } else {
            todayRunTimeUTC
        }

        return ChronoUnit.MILLIS.between(nowUTC, nextRunTimeUTC)
    }

    /** Downgrade roles for those who no longer meet thresholds */
    private suspend fun downgradeRoles() {
        canUpdateRolesOrDbMutex.withLock {
            val today = getTodaysDate()
            updateMembershipRolesService.updateMembershipRolesForAllUsers(today)
        }
    }


    companion object {
        internal fun getTodaysDate() = LocalDate.now(ZoneOffset.UTC)
    }
}