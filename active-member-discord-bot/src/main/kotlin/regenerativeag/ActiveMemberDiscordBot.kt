package regenerativeag

import regenerativeag.model.*
import java.time.LocalDate

class ActiveMemberDiscordBot(
    private val database: Database,
    private val discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
) {
    private val lock = object { }
    private val roleNameByRoleId = discord.server.fetchAllRoles(activeMemberConfig.serverId)

    fun login() {
        cleanReload()

        scheduleRecurringCleanup()

        discord.bot.login(::onMessage)
    }


    /** Read the whole message history, correct any issues in the DB, and correct any issues in people's roles */
    private fun cleanReload() {
        println("Reloading")
        synchronized(lock) {
            val today = LocalDate.now()

            val postHistory = discord.postHistory.fetch(
                    activeMemberConfig,
                    today,
            )
            println("Overwriting post history: $postHistory")
            database.overwritePostHistory(postHistory)

            val computedMembersSets = computeActiveMembers(postHistory, today, activeMemberConfig)
            val departedUserIds = mutableSetOf<UserId>()
            val memberIdsWhoHadARoleBeforeRunning = mutableSetOf<UserId>()
            val memberIdsWhoHaveARoleAfterRunning = mutableSetOf<UserId>()
            computedMembersSets.zip(activeMemberConfig.roleConfigs).forEach { (computedMemberIds, roleConfig) ->
                println("")
                val roleName = roleNameByRoleId[roleConfig.roleId]
                val computedMemberNames = discord.users.mapUserIdsToNames(computedMemberIds)
                val currentMemberIds = discord.users.getUsersWithRole(activeMemberConfig.serverId, roleConfig.roleId)
                val currentMemberNames = discord.users.mapUserIdsToNames(currentMemberIds)
                val userIdsMeetingThreshold = computedMemberIds - currentMemberIds
                val userNamesMeetingThreshold = discord.users.mapUserIdsToNames(userIdsMeetingThreshold)
                println("Current members in $roleName (${currentMemberIds.size}): $currentMemberNames")
                println("Computed members in $roleName (${computedMemberIds.size}): $computedMemberNames")
                println("New members to add to $roleName (${userIdsMeetingThreshold.size}): $userNamesMeetingThreshold")
                val userIdsToAdd = discord.users.filterToUsersCurrentlyInGuild(
                    activeMemberConfig.serverId,
                    userIdsMeetingThreshold
                )
                val usersWhoLeftButMetThreshold = userIdsMeetingThreshold - userIdsToAdd
                departedUserIds.addAll(usersWhoLeftButMetThreshold)
                discord.users.addActiveRole(activeMemberConfig, roleConfig, userIdsToAdd)
                memberIdsWhoHadARoleBeforeRunning.addAll(currentMemberIds)
                memberIdsWhoHaveARoleAfterRunning.addAll(computedMemberIds)
            }

            println("")
            println("Members who met a threshold, but left: ${discord.users.mapUserIdsToNames(departedUserIds)}")
            val inactiveMemberIds = memberIdsWhoHadARoleBeforeRunning - memberIdsWhoHaveARoleAfterRunning
            println("Members who no longer meet a threshold: ${discord.users.mapUserIdsToNames(inactiveMemberIds)}")
        }
        println("Reload complete")
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
            for (roleConfig in activeMemberConfig.roleConfigs.reversed()) {
                val meetsThreshold = meetsThreshold(roleConfig, postDays, LocalDate.now())
                if (meetsThreshold) {
                    println("(Re)adding ${roleNameByRoleId[roleConfig.roleId]} for \"${message.userId}\".")
                    discord.users.addActiveRole(activeMemberConfig, roleConfig, setOf(message.userId))
                    break
                }
            }
        }
    }

    private fun scheduleRecurringCleanup() {
        // TODO: https://github.com/orgs/regenerativeag/projects/1/views/1?pane=issue&itemId=69255754
    }

    companion object {

        internal fun meetsThreshold(roleConfig: ActiveMemberConfig.RoleConfig, postDays: Set<LocalDate>, today: LocalDate): Boolean {
            val earliestAddDate = today.minusDays(roleConfig.addRoleConfig.windowSize - 1L)
            val earliestKeepDate = today.minusDays(roleConfig.keepRoleConfig.windowSize - 1L)
            val meetsAddThreshold = postDays.filter { date -> date >=  earliestAddDate }.size >= roleConfig.addRoleConfig.minPostDays
            val meetsKeepThreshold = postDays.filter { date -> date >= earliestKeepDate }.size >= roleConfig.keepRoleConfig.minPostDays
            return meetsAddThreshold && meetsKeepThreshold
        }

        internal fun computeActiveMembers(
                postHistory: PostHistory,
                today: LocalDate,
                activeMemberConfig: ActiveMemberConfig,
        ): List<Set<UserId>> {
            val usersByRoleIndex = activeMemberConfig.roleConfigs.map { roleConfig ->
                val earliestAddDate = today.minusDays(roleConfig.addRoleConfig.windowSize - 1L)
                val earliestKeepDate = today.minusDays(roleConfig.keepRoleConfig.windowSize - 1L)

                val membersMeetingThreshold = postHistory.filterValues { meetsThreshold(roleConfig, it, today) }.keys
                membersMeetingThreshold - activeMemberConfig.excludedUserIds
            }

            val seen = mutableSetOf<UserId>()
            // user should only get the latest role they are qualified for
            return usersByRoleIndex.reversed().map { usersInRole ->
                val usersToKeep = usersInRole - seen
                seen.addAll(usersToKeep)
                usersToKeep
            }.reversed()
        }
    }
}