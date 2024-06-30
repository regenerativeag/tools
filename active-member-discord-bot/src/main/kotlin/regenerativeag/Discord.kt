package regenerativeag

import dev.kord.common.entity.DiscordGuildMember
import dev.kord.common.entity.Snowflake
import dev.kord.gateway.DefaultGateway
import dev.kord.gateway.MessageCreate
import dev.kord.gateway.start
import dev.kord.rest.builder.message.AllowedMentionsBuilder
import dev.kord.rest.request.KtorRequestException
import dev.kord.rest.request.KtorRequestHandler
import dev.kord.rest.route.Position
import dev.kord.rest.service.RestClient
import io.ktor.client.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import regenerativeag.discord.*
import regenerativeag.model.*
import java.time.LocalDate

class Discord(
    httpClient: HttpClient,
    private val token: String,
) {
    private val restClient = RestClient(KtorRequestHandler(httpClient, token = token))
    private val usernameCache = UsernameCache(restClient)
    private val channelNameCache = ChannelNameCache(restClient)

    val postHistory = _PostHistory()
    inner class _PostHistory {
        /** Query dicord for enough recent post history that active member roles can be computed */
        fun fetch(activeMemberConfig: ActiveMemberConfig, today: LocalDate): PostHistory {
            val fetcher = PostHistoryFetcher(
                restClient,
                activeMemberConfig.serverId,
                activeMemberConfig.maxWindowSize,
                today,
                channelNameCache
            )
            return fetcher.fetch()
        }
    }

    val users = _Users()
    inner class _Users {
        fun mapUserIdsToNames(userIds: Iterable<UserId>): List<String> {
            return userIds.map { usernameCache.lookup(it) }
        }

        /** Of the users provided, only return the users which are still in the guild */
        fun filterToUsersCurrentlyInGuild(guildId: ULong, userIds: Set<UserId>): Set<UserId> {
            val sGuildId = Snowflake(guildId)
            return userIds.filter {
                // TODO: Parallelize requests - https://github.com/regenerativeag/tools/issues/1
                runBlocking {
                    try {
                        restClient.guild.getGuildMember(sGuildId, Snowflake(it))
                        true
                    } catch (e: KtorRequestException) {
                        if (e.status.code == 404) {
                            false
                        } else {
                            throw e
                        }
                    }
                }
            }.toSet()
        }

        /** Fetch the users with the given roleId */
        fun getUsersWithRole(guildId: ULong, role: ULong): Set<UserId> {
            val sGuildId = Snowflake(guildId)
            val sRole = Snowflake(role)
            val limit = 100
            val members = mutableListOf<DiscordGuildMember>()
            runBlocking {
                do {
                    val page = restClient.guild.getGuildMembers(
                        sGuildId,
                        limit = limit,
                        after = members.lastOrNull()?.let { Position.After(it.user.value!!.id) }
                    )
                    members.addAll(page)
                    page.forEach { usernameCache.cacheFrom(it) }
                } while (page.size == limit)
            }
            return members
                .filter { sRole in it.roles }
                .mapNotNull { it.user.value?.id?.value}
                .toSet()
        }

        /**
         * Add an active member role to a user.
         *
         * If the user already has some other active member role, remove that role.
         */
        fun addActiveRole(activeMemberConfig: ActiveMemberConfig, roleConfig: ActiveMemberConfig.RoleConfig, userIds: Set<UserId>, ) {
            val userPairs = userIds.map { it to usernameCache.lookup(it) }
            val sServerId = Snowflake(activeMemberConfig.serverId)
            val sRoleId = Snowflake(roleConfig.roleId)
            val roleIdxByRoleId = activeMemberConfig.roleConfigs.mapIndexed { idx, cfg ->
                cfg.roleId to idx
            }.toMap()
            runBlocking {
                // TODO: Parallelize requests - https://github.com/regenerativeag/tools/issues/1
                userPairs.forEach { (userId, username) ->
                    val sUserId = Snowflake(userId)
                    val userRoles = restClient.guild.getGuildMember(sServerId, sUserId).roles.map { it.value }.toSet()
                    if (roleConfig.roleId in userRoles) {
                        println("$username already has role ${roleConfig.roleId}")
                    } else {
                        val rolesToRemove = activeMemberConfig.roleConfigs.map { it.roleId }.toSet() - roleConfig.roleId

                        // TODO: Parallelize requests - https://github.com/regenerativeag/tools/issues/1
                        rolesToRemove.forEach { roleIdToRemove ->
                            if (roleIdToRemove in userRoles) {
                                removeActiveRole(activeMemberConfig.serverId, roleIdToRemove, userId)
                            }
                        }
                        restClient.guild.addRoleToGuildMember(sServerId, sUserId, sRoleId)

                        val currentRoleLevel = userRoles.mapNotNull { roleIdxByRoleId[it] }.maxOrNull()
                        val newRoleLevel = roleIdxByRoleId[roleConfig.roleId]!!
                        val shouldWelcome = if (currentRoleLevel == null) {
                            true
                        } else {
                            newRoleLevel > currentRoleLevel
                        }
                        if (shouldWelcome) {
                            val welcomeConfig = roleConfig.welcomeMessageConfig
                            restClient.channel.createMessage(Snowflake(welcomeConfig.channel)) {
                                this.content = welcomeConfig.prefix + "<@$userId>" + welcomeConfig.suffix
                                val mentions = AllowedMentionsBuilder()
                                mentions.users.add(Snowflake(userId))
                                this.allowedMentions = mentions
                            }
                        }
                    }
                }
            }
        }

        fun removeAllActiveRolesFromUser(activeMemberConfig: ActiveMemberConfig, userId: UserId) {
            val username = usernameCache.lookup(userId)
            val sServerId = Snowflake(activeMemberConfig.serverId)
            runBlocking {
                val sUserId = Snowflake(userId)
                val userRoles = restClient.guild.getGuildMember(sServerId, sUserId).roles.map { it.value }.toSet()
                val activeRoles = activeMemberConfig.roleConfigs.map { it.roleId }.toSet()
                val userActiveRoles = userRoles.intersect(activeRoles)
                if (userActiveRoles.isEmpty()) {
                    println("$username has no active roles to remove")
                } else {
                    userActiveRoles.forEach {
                        val sRoleId = Snowflake(it)
                        restClient.guild.deleteRoleFromGuildMember(sServerId, sUserId, sRoleId)
                        println("Removed role=$it from $username")
                    }
                }
            }
        }

        /** Remove the role from the user.
         * This function is private, as it likely should not be used directly...
         *  - use [addActiveRole] to transition users between roles
         *  - use [removeAllActiveRolesFromUser] to remove all active roles from a user
         */
        private fun removeActiveRole(serverId: ServerId, roleId: RoleId, userId: UserId) {
            val username = usernameCache.lookup(userId)
            val sServerId = Snowflake(serverId)
            val sRoleId = Snowflake(roleId)
            runBlocking {
                val sUserId = Snowflake(userId)
                val userRoles = restClient.guild.getGuildMember(sServerId, sUserId).roles.map { it.value }
                if (roleId !in userRoles) {
                    println("$username doesn't have role $roleId to remove")
                } else {
                    restClient.guild.deleteRoleFromGuildMember(sServerId, sUserId, sRoleId)
                }
            }
        }
    }

    val server = _Server()
    inner class _Server {
        /** Fetch all roles in the server */
        fun fetchAllRoles(serverId: ServerId): Map<RoleId, String> {
            val sServerId = Snowflake(serverId)
            return runBlocking {
                restClient.guild.getGuildRoles(sServerId).associate {
                    it.id.value to it.name
                }
            }
        }
    }

    val bot = _Bot()
    inner class _Bot {
        /** Log the bot into discord and call the callback whenever a message is received */
        fun login(onMessage: (Message) -> Unit) {
            val gateway = DefaultGateway()

            gateway.events.filterIsInstance<MessageCreate>().onEach { messageCreate ->
                with(messageCreate.message) {
                    val channelName = channelNameCache.lookup(this.channelId.value)
                    val threadName = this.thread.value?.name
                    val userId = this.getUserId()
                    val username = usernameCache.lookup(userId)
                    val localDate = this.getLocalDate()
                    println("Message received from $username on $localDate in (channel='$channelName', thread='$threadName')")
                    onMessage(Message(userId, localDate))
                }
            }.launchIn(gateway)

            runBlocking {
                gateway.start(token) {
                    // use defaults... nothing to do here.
                }
            }
        }
    }
}