package org.regenagcoop.discord

import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.regenagcoop.Database
import org.regenagcoop.coroutine.TopLevelJob.Companion.awaitEndlessJobs
import org.regenagcoop.coroutine.TopLevelJob.Companion.createTopLevelJob
import org.regenagcoop.discord.service.FetchActivityService
import org.regenagcoop.discord.service.MembershipRoleService
import org.regenagcoop.discord.service.ResetMembershipsService
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.PostHistory
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.Reaction
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.discord.service.PersistedActivityService
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
    private val membershipRoleService = MembershipRoleService(discord, activeMemberConfig)
    private val persistedActivityService = PersistedActivityService(discord, activeMemberConfig)
    private val fetchActivityService = FetchActivityService(discord, persistedActivityService)
    private val resetMembershipsService = ResetMembershipsService(discord, membershipRoleService, activeMemberConfig)

    private val bot = DiscordBot(
        discord,
        discordApiToken,
        onMessage = ::onMessage,
        onReaction = ::onReaction
    )


    fun start() {
        val startupDate = getTodaysDate()

        // Load the in-memory database by fetching from Discord & persist any post data that was missing in the persistence channel
        val loadDatabaseJob = createTopLevelJob(
            name = "load database"
        ) {
            canUpdateRolesOrDbMutex.withLock {
                logger.debug { "Loading Database" }
                val (activityHistory, persistedDates) = fetchActivityService.fetchActivityHistory(startupDate)

                logger.debug { "Initializing in-memory database: $activityHistory" }
                database.initialize(activityHistory)

                logger.debug { "Persisting missing post history into persistence channel" }
                persistedActivityService.persistMissingPostHistory(
                    startupDate,
                    activityHistory.postHistory,
                    persistedDates
                )
            }
        }

        // Compute roles from database & reset roles for all users
        val resetRolesJob = createTopLevelJob(
            name = "reset roles",
            dependencies = listOf(loadDatabaseJob)
        ){
            logger.debug { "Resetting roles" }
            val postHistory = database.getPostHistory()
            resetMembershipsService.resetRolesGivenPostHistory(postHistory, startupDate)
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
                val posters = database.getUsersWhoPostedOnDay(yesterday)
                logger.debug { "Persisting yesterday's ($yesterday) post history: ${posters.sorted()}" }
                persistedActivityService.persistPostHistoryForDay(yesterday, posters)
            }
        }

        // In the main thread, block until all jobs are complete
        // Some jobs are endless, so the only way to exit this program is for the user to press Ctl+C or Cmd+C or kill the process.
        awaitEndlessJobs(listenForDiscordEventsJob, dailyJob)
    }

    /** If this message results in the user meeting an active-member threshold, adjust the user's roles. */
    private suspend fun onMessage(message: Message) {
        if (message.userId in activeMemberConfig.excludedUserIds) {
            return
        }

        canUpdateRolesOrDbMutex.withLock {
            val (isFirstPostOfDay, postDays) = database.addPost(message.userId, message.utcDate)
            if (!isFirstPostOfDay) {
                return
            }
            // check roles in reverse order, so that user is granted the highest role they are qualified for
            for (roleConfig in activeMemberConfig.roleConfigs.reversed()) {
                val meetsThreshold = meetsThreshold(roleConfig, postDays, message.utcDate)
                if (meetsThreshold) {
                    val roleName = discord.roleNameCache.lookup(roleConfig.roleId)
                    val username = discord.usernameCache.lookup(message.userId)
                    logger.debug { "(Re)adding $roleName for $username (${message.userId})." }
                    membershipRoleService.addMembershipRoleToUsers(roleConfig, setOf(message.userId))
                    break
                }
            }
        }
    }

    /** Update the reaction history in the database & persist reaction in persistence channel */
    private suspend fun onReaction(reaction: Reaction) {
        if (reaction.userId in activeMemberConfig.excludedUserIds) {
            return
        }

        canUpdateRolesOrDbMutex.withLock {
            // TODO #16: add reaction to DB
            // TODO #16: if this is the user's first reaction of day, persist reaction in persistence channel
            throw NotImplementedError()
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
            val postHistory = database.getPostHistory()
            resetMembershipsService.resetRolesGivenPostHistory(postHistory, today)
        }
    }


    companion object {
        internal fun getTodaysDate() = LocalDate.now(ZoneOffset.UTC)

        /** Determine whether the user should have the role identified by [roleConfig], given the user's [postDays] */
        internal fun meetsThreshold(roleConfig: ActiveMemberConfig.RoleConfig, postDays: Set<LocalDate>, today: LocalDate): Boolean {
            val earliestAddDate = today.minusDays(roleConfig.addRoleConfig.windowSize - 1L)
            val earliestKeepDate = today.minusDays(roleConfig.keepRoleConfig.windowSize - 1L)
            val meetsAddThreshold = postDays.filter { date -> date >=  earliestAddDate }.size >= roleConfig.addRoleConfig.minPostDays
            val meetsKeepThreshold = postDays.filter { date -> date >= earliestKeepDate }.size >= roleConfig.keepRoleConfig.minPostDays
            return meetsAddThreshold && meetsKeepThreshold
        }

        /**
         * Return the members that should be in each role.
         * The result is in the same order as [ActiveMemberConfig.roleConfigs]
         */
        internal fun computeActiveMembers(
            postHistory: PostHistory,
            today: LocalDate,
            activeMemberConfig: ActiveMemberConfig,
        ): List<Set<UserId>> {
            val usersByRoleIndex = activeMemberConfig.roleConfigs.map { roleConfig ->
                val membersMeetingThreshold = postHistory.filterValues { meetsThreshold(roleConfig, it, today) }.keys
                membersMeetingThreshold - activeMemberConfig.excludedUserIds
            }

            val seen = mutableSetOf<UserId>()
            // user should only get the highest-priority role they are qualified for
            return usersByRoleIndex.reversed().map { usersInRole ->
                val usersToKeep = usersInRole - seen
                seen.addAll(usersToKeep)
                usersToKeep
            }.reversed()
        }
    }
}