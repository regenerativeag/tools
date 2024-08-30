package org.regenagcoop.discord

import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.regenagcoop.Database
import org.regenagcoop.discord.client.FetchPostHistoryClient
import org.regenagcoop.discord.client.MembershipRoleClient
import org.regenagcoop.discord.client.ResetMembershipsClient
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.PostHistory
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
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
    private val membershipRoleClient = MembershipRoleClient(discord, activeMemberConfig)
    private val fetchPostHistoryClient = FetchPostHistoryClient(discord, activeMemberConfig)
    private val resetMembershipsClient = ResetMembershipsClient(discord, membershipRoleClient, activeMemberConfig)
    private val bot = DiscordBot(discord, discordApiToken, onMessage = ::onMessage)

    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        val startupDate = LocalDate.now()
        val uncaughtExceptionHandler = CoroutineExceptionHandler { _, exception ->
            logger.error(exception) { "Uncaught Exception" }
            fun printSuppressedRecursive(throwable: Throwable) {
                throwable.suppressedExceptions.forEach {
                    logger.error(it) { "Suppressed Exception" }
                    printSuppressedRecursive(it)
                }
            }
            printSuppressedRecursive(exception)
        }

        /**
         * Launch a new top-level coroutine in the default thread pool
         * Wait until [dependencies] have succeeded before launching the coroutine
         */
        fun launchTopLevelJob(name: String, vararg dependencies: Job, coroutine: suspend () -> Unit): Job {
            return GlobalScope.launch(uncaughtExceptionHandler) {
                dependencies.toList().joinAll()
                val anyDependencyFailed = dependencies.any { it.isCancelled }
                if (anyDependencyFailed) {
                    logger.warn { "Not running top-level job '$name' because at least one dependency failed" }
                } else {
                    try {
                        coroutine()
                    } catch (e: Throwable) {
                        logger.warn { "Top-level job '$name' failed" }
                        throw e
                    }
                }
            }
        }

        val reloadDatabaseJob = launchTopLevelJob("reload database") {
            canUpdateRolesOrDbMutex.withLock {
                logger.debug { "Reloading Database" }
                val postHistory = fetchPostHistoryClient.fetchPostHistory(startupDate)

                logger.debug { "Overwriting post history: $postHistory" }
                database.overwritePostHistory(postHistory)
            }
        }

        // Reset all roles after the database has been reloaded
        val resetRolesJob = launchTopLevelJob("reset roles", reloadDatabaseJob){
            logger.debug { "Resetting roles" }
            val postHistory = database.getPostHistory()
            resetMembershipsClient.resetRolesGivenPostHistory(postHistory, startupDate)
        }

        // ENDLESSLY listen for websocket events from discord.
        val listenForDiscordEventsJob = launchTopLevelJob(
            "listen for events",
            reloadDatabaseJob,
            resetRolesJob
        ) {
            logger.debug { "Listening for discord events" }
            bot.login() // endlessly listen for websocket events from discord
            // Events listened to:
            // onMessage: If the user's post qualified them for a new role
            //   - give them the role
            //   - welcome them with a message
        }

        // ENDLESSLY do daily tasks
        val dailyJob = launchTopLevelJob("daily tasks", reloadDatabaseJob, resetRolesJob) {
            while (true) {
                // Pause this coroutine until 12:05 am UTC
                val delayMs = millisUntilNextDailyJobExecution()
                val nextExecutionLocalTime = LocalDateTime.now().plus(delayMs, ChronoUnit.MILLIS)
                logger.debug { "Next execution of daily job scheduled for $nextExecutionLocalTime" }
                delay(delayMs)

                logger.debug { "Daily Job started" }

                logger.debug { "Downgrading roles for users who no longer meet threshold" }
                downgradeRoles()
            }
        }

        // In the main thread, block until all jobs are complete
        // Some jobs are endless, so the only way to exit this program is for the user to press Ctl+C or Cmd+C or kill the process.
        runBlocking {
            listOf(
                reloadDatabaseJob,
                resetRolesJob,
                listenForDiscordEventsJob,
                dailyJob
            ).joinAll()
        }
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
                val meetsThreshold = meetsThreshold(roleConfig, postDays, LocalDate.now())
                if (meetsThreshold) {
                    val roleName = discord.roleNameCache.lookup(roleConfig.roleId)
                    val username = discord.usernameCache.lookup(message.userId)
                    logger.debug { "(Re)adding $roleName for $username (${message.userId})." }
                    membershipRoleClient.addMembershipRoleToUsers(roleConfig, setOf(message.userId))
                    break
                }
            }
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
            val today = LocalDate.now()
            val postHistory = database.getPostHistory()
            resetMembershipsClient.resetRolesGivenPostHistory(postHistory, today)
        }
    }


    companion object {

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