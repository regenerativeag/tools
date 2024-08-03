package regenerativeag

import io.ktor.client.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import regenerativeag.model.*
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class ActiveMemberDiscordBot(
    httpClient: HttpClient,
    discordApiToken: String,
    dryRun: Boolean,
    private val database: Database,
    private val activeMemberConfig: ActiveMemberConfig,
) {
    private val logger = KotlinLogging.logger {  }
    private val lock = object { }
    private val discord = Discord(httpClient, activeMemberConfig, discordApiToken, dryRun)


    fun login() {
        Reloader().cleanReload()

        scheduleRecurringRoleDowngrading()

        discord.bot.login(::onMessage)
    }

    /** If this message results in the user meeting an active-member threshold, adjust the user's roles. */
    private fun onMessage(message: Message) {
        if (message.userId in activeMemberConfig.excludedUserIds) {
            return
        }

        synchronized(lock) {
            val (isFirstPostOfDay, postDays) = database.addPost(message.userId, message.date)
            if (!isFirstPostOfDay) {
                return
            }
            // check roles in reverse order, so that user is granted the highest role they are qualified for
            for (roleConfig in activeMemberConfig.roleConfigs.reversed()) {
                val meetsThreshold = meetsThreshold(roleConfig, postDays, LocalDate.now())
                if (meetsThreshold) {
                    val roleName = discord.roleNameCache.lookup(activeMemberConfig.guildId, roleConfig.roleId)
                    logger.debug { "(Re)adding $roleName for \"${message.userId}\"." }
                    discord.users.addActiveRole(roleConfig, setOf(message.userId))
                    break
                }
            }
        }
    }

    /** schedule a recurring job that downgrades memberships for those who haven't posted in a while */
    @OptIn(DelicateCoroutinesApi::class)
    private fun scheduleRecurringRoleDowngrading() {
        fun millisUntilNextRun(): Long {
            val now = ZonedDateTime.now()
            val today = ZonedDateTime.of(now.year, now.monthValue, now.dayOfMonth, 0, 0, 0, 0, now.zone)
            val nextRunTime = today.plusDays(1).plusMinutes(5)
            return ChronoUnit.MILLIS.between(now, nextRunTime)
        }

        suspend fun downgradeAndReschedule() {
            logger.debug { "Looking for members who no longer meet role thresholds..." }
            Reloader().downgradeRoles()
            delay(millisUntilNextRun())
            downgradeAndReschedule()
        }

        GlobalScope.launch {
            delay(millisUntilNextRun())
            downgradeAndReschedule()
        }

    }

    /** Reload DB state from discord, and ensure everyone's roles are up-to-date */
    private inner class Reloader {
        private val departedUserIds = mutableSetOf<UserId>()
        private val memberIdsWhoHadARoleBeforeRunning = mutableSetOf<UserId>()
        private val memberIdsWhoHaveARoleAfterRunning = mutableSetOf<UserId>()

        /** Read the whole message history, correct any issues in the DB, and correct any issues in people's roles */
        fun cleanReload() {
            logger.debug { "Reloading" }

            val today = LocalDate.now()
            val postHistory = discord.postHistory.fetch(today)

            synchronized(lock) {
                logger.debug { "Overwriting post history: $postHistory" }
                database.overwritePostHistory(postHistory)

                updateMemberRolesGivenPostHistory(postHistory, today)
            }

            logger.debug { "Reload complete" }
        }

        /** Downgrade roles for those who no longer meet thresholds */
        fun downgradeRoles() {
            synchronized(lock) {
                val today = LocalDate.now()
                val postHistory = database.getPostHistory()
                updateMemberRolesGivenPostHistory(postHistory, today)
            }
        }

        private fun updateMemberRolesGivenPostHistory(postHistory: PostHistory, today: LocalDate) {
            // Iterate over every role, updating all members in that role
            val computedMembersSets = computeActiveMembers(postHistory, today, activeMemberConfig)
            computedMembersSets.zip(activeMemberConfig.roleConfigs).forEach {
                updateRoleMembers(it.first, it.second)
            }

            logger.debug { "Members who met a threshold, but left: ${discord.users.mapUserIdsToNames(departedUserIds)}" }
            val inactiveMemberIds = memberIdsWhoHadARoleBeforeRunning - memberIdsWhoHaveARoleAfterRunning
            logger.debug { "Members who no longer meet a threshold: ${discord.users.mapUserIdsToNames(inactiveMemberIds)}" }
            inactiveMemberIds.forEach { inactiveMemberId ->
                discord.users.removeAllActiveRolesFromUser(inactiveMemberId)
            }
        }

        /** Ensure that the role identified by [roleConfig] includes exactly the members in [computedMemberIds] */
        private fun updateRoleMembers(computedMemberIds: Set<UserId>, roleConfig: ActiveMemberConfig.RoleConfig) {
            val roleName = discord.roleNameCache.lookup(activeMemberConfig.guildId, roleConfig.roleId)
            fun log(prefix: String, userIds: Set<UserId>) {
                logger.debug { "$prefix $roleName (${userIds.size}): ${discord.users.mapUserIdsToNames(userIds).sorted()}" }
            }

            log("Computed members in", computedMemberIds)

            val currentMemberIds = discord.users.getUsersWithRole(roleConfig.roleId)
            log("Current members in", currentMemberIds)

            val userIdsToAdd = computedMemberIds - currentMemberIds
            log("New members to add to", userIdsToAdd)
            val retainedUserIdsToAdd = discord.users.filterToUsersCurrentlyInGuild(
                userIdsToAdd
            )
            val usersWhoLeftButMetThreshold = userIdsToAdd - retainedUserIdsToAdd
            departedUserIds.addAll(usersWhoLeftButMetThreshold)
            discord.users.addActiveRole(roleConfig, retainedUserIdsToAdd)

            memberIdsWhoHadARoleBeforeRunning.addAll(currentMemberIds)
            memberIdsWhoHaveARoleAfterRunning.addAll(computedMemberIds)
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