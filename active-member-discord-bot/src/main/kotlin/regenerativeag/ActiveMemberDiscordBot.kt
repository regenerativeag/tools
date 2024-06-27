package regenerativeag

import regenerativeag.model.*
import java.time.LocalDate

class ActiveMemberDiscordBot(
        private val database: Database,
        private val discord: Discord,
) {
    private val lock = object { }
    private val roleNameByRoleId = discord.getServerRoles(loadConfig().serverId)

    fun login() {
        cleanReload()

        scheduleRecurringCleanup()

        discord.login(::onMessage)
    }


    /** Read the whole message history, correct any issues in the DB, and correct any issues in people's roles */
    private fun cleanReload() {
        println("Reloading")
        synchronized(lock) {
            val activeMemberConfig = loadConfig()
            val today = LocalDate.now()

            val postHistory = discord.getPostHistory(
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
                val computedMemberNames = discord.mapUserIdsToNames(computedMemberIds)
                val currentMemberIds = discord.getUsersWithRole(activeMemberConfig.serverId, roleConfig.roleId)
                val currentMemberNames = discord.mapUserIdsToNames(currentMemberIds)
                val userIdsMeetingThreshold = computedMemberIds - currentMemberIds
                val userNamesMeetingThreshold = discord.mapUserIdsToNames(userIdsMeetingThreshold)
                println("Current members in $roleName (${currentMemberIds.size}): $currentMemberNames")
                println("Computed members in $roleName (${computedMemberIds.size}): $computedMemberNames")
                println("New members to add to $roleName (${userIdsMeetingThreshold.size}): $userNamesMeetingThreshold")
                val userIdsToAdd = discord.filterToUsersCurrentlyInGuild(
                    activeMemberConfig.serverId,
                    userIdsMeetingThreshold
                )
                val usersWhoLeftButMetThreshold = userIdsMeetingThreshold - userIdsToAdd
                departedUserIds.addAll(usersWhoLeftButMetThreshold)
                discord.addActiveRole(activeMemberConfig, roleConfig, userIdsToAdd)
//                if (roleName == "Active Member") {
//                    discord.addActiveRole(activeMemberConfig, roleConfig, setOf(1003068122997207060u))
//                }
                memberIdsWhoHadARoleBeforeRunning.addAll(currentMemberIds)
                memberIdsWhoHaveARoleAfterRunning.addAll(computedMemberIds)
            }

            println("")
            println("Members who met a threshold, but left: ${discord.mapUserIdsToNames(departedUserIds)}")
            val inactiveMemberIds = memberIdsWhoHadARoleBeforeRunning - memberIdsWhoHaveARoleAfterRunning
            println("Members who no longer meet a threshold: ${discord.mapUserIdsToNames(inactiveMemberIds)}")
        }
        println("Reload complete")
    }

    private fun onMessage(message: Message) {
        val activeMemberConfig = loadConfig()
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
                    discord.addActiveRole(activeMemberConfig, roleConfig, setOf(message.userId))
                    break
                }
            }
        }
    }

    private fun scheduleRecurringCleanup(): Unit {} // TODO

    /** remove post history that isn't needed from the DB, and unassign users who haven't posted in a while */
    private fun recurringCleanup() {
        synchronized(lock) {
            throw NotImplementedError()
        }
    }

    private fun loadConfig() = ActiveMemberConfig(
            1162017936656044042u, // guildId
            setOf(
                1221517195931156530uL, // Active Member Bot (Larry)
                302050872383242240uL,  // Disboard
                476259371912003597uL,  // Discord.me
                155149108183695360uL,  // Dyno
                204255221017214977uL,  // YAGPDB.xyz
            ),
            listOf(
                // Guest
                ActiveMemberConfig.RoleConfig(
                    1240396803946582056u,
                    AddRoleConfig(60, 1),
                    KeepRoleConfig(60, 1),
                    ActiveMemberConfig.WelcomeMessageConfig(
                        1162017937796911209u,
                        "A warm hello to our newest guest, ",
                        " :relaxed: Please check out <#1240458302530519123> when you have a moment.",
                    )
                ),
                // Active Member
                ActiveMemberConfig.RoleConfig(
                    1223026651340996698u,
                    AddRoleConfig(60, 4),
                    KeepRoleConfig(30, 1),
                    ActiveMemberConfig.WelcomeMessageConfig(
                        1223262623194153111u,
                        "Welcome to our community, ",
                        "!",
                    )
                )
            )
    )

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