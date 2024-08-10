package org.regenagcoop.discord

import io.ktor.client.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.regenagcoop.Database
import org.regenagcoop.discord.client.MembershipRoleClient
import org.regenagcoop.discord.client.ResetMembershipsClient
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.PostHistory
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
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
    private val canUpdateRolesOrDbLock = object { }
    private val discord = Discord(httpClient, activeMemberConfig.guildId, discordApiToken, dryRun)
    private val membershipRoleClient = MembershipRoleClient(discord, activeMemberConfig)
    private val resetMembershipsClient = ResetMembershipsClient(discord, database, membershipRoleClient, activeMemberConfig)
    private val bot = DiscordBot(discord, discordApiToken, onMessage = ::onMessage)

    fun login() {
        synchronized(canUpdateRolesOrDbLock) {
            resetMembershipsClient.cleanReload()
        }

        scheduleRecurringRoleDowngrading()

        bot.login()
    }

    /** If this message results in the user meeting an active-member threshold, adjust the user's roles. */
    private fun onMessage(message: Message) {
        if (message.userId in activeMemberConfig.excludedUserIds) {
            return
        }

        synchronized(canUpdateRolesOrDbLock) {
            val (isFirstPostOfDay, postDays) = database.addPost(message.userId, message.date)
            if (!isFirstPostOfDay) {
                return
            }
            // check roles in reverse order, so that user is granted the highest role they are qualified for
            for (roleConfig in activeMemberConfig.roleConfigs.reversed()) {
                val meetsThreshold = meetsThreshold(roleConfig, postDays, LocalDate.now())
                if (meetsThreshold) {
                    val roleName = discord.roleNameCache.lookup(roleConfig.roleId)
                    logger.debug { "(Re)adding $roleName for \"${message.userId}\"." }
                    membershipRoleClient.addMembershipRoleToUsers(roleConfig, setOf(message.userId))
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

        GlobalScope.launch {
            while (true) {
                delay(millisUntilNextRun())
                logger.debug { "Looking for members who no longer meet role thresholds..." }
                downgradeRoles()
            }
        }
    }
    /** Downgrade roles for those who no longer meet thresholds */
    private fun downgradeRoles() {
        synchronized(canUpdateRolesOrDbLock) {
            val today = LocalDate.now()
            val postHistory = database.getPostHistory()
            resetMembershipsClient.updateMemberRolesGivenPostHistory(postHistory, today)
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