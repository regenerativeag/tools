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
import mu.KotlinLogging
import regenerativeag.discord.*
import regenerativeag.model.*
import java.time.LocalDate

class Discord(
    httpClient: HttpClient,
    private val token: String,
    private val dryRun: Boolean,
) {
    private val logger = KotlinLogging.logger { }
    private val restClient = RestClient(KtorRequestHandler(httpClient, token = token))
    private val usernameCache = UsernameCache(restClient)
    private val channelNameCache = ChannelNameCache(restClient)
    val roleNameCache = RoleNameCache(restClient)

    val postHistory = _PostHistory()
    inner class _PostHistory {
        /** Query dicord for enough recent post history that active member roles can be computed */
        fun fetch(activeMemberConfig: ActiveMemberConfig, today: LocalDate): PostHistory {
            val fetcher = PostHistoryFetcher(
                restClient,
                activeMemberConfig.guildId,
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
            val sServerId = Snowflake(activeMemberConfig.guildId)
            val sRoleId = Snowflake(roleConfig.roleId)
            val roleName = roleNameCache.lookup(activeMemberConfig.guildId, roleConfig.roleId)
            val roleIdxByRoleId = activeMemberConfig.roleConfigs.mapIndexed { idx, cfg ->
                cfg.roleId to idx
            }.toMap()
            runBlocking {
                // TODO: Parallelize requests - https://github.com/regenerativeag/tools/issues/1
                userPairs.forEach { (userId, username) ->
                    val sUserId = Snowflake(userId)
                    val userRoles = restClient.guild.getGuildMember(sServerId, sUserId).roles.map { it.value }.toSet()
                    if (roleConfig.roleId in userRoles) {
                        logger.debug { "$username already has role ${roleConfig.roleId} ($roleName)" }
                    } else {
                        val rolesToRemove = activeMemberConfig.roleConfigs.map { it.roleId }.toSet() - roleConfig.roleId

                        // TODO: Parallelize requests - https://github.com/regenerativeag/tools/issues/1
                        rolesToRemove.forEach { roleIdToRemove ->
                            if (roleIdToRemove in userRoles) {
                                removeActiveRole(activeMemberConfig.guildId, roleIdToRemove, userId)
                            }
                        }
                        if (rolesToRemove.size > 1) {
                            logger.warn("Expected at most one role to remove while adding a role to a user... Removing $rolesToRemove from $userId")
                        }

                        if (dryRun) {
                            logger.info("Dry run... would have added role=$roleName to $username")
                        } else {
                            restClient.guild.addRoleToGuildMember(sServerId, sUserId, sRoleId)
                            logger.info("Added role $roleName to $username")
                        }

                        val currentRoleLevel = userRoles.mapNotNull { roleIdxByRoleId[it] }.maxOrNull()
                        val newRoleLevel = roleIdxByRoleId[roleConfig.roleId]!!
                        val isUpgrade = currentRoleLevel == null || newRoleLevel > currentRoleLevel
                        if (isUpgrade) {
                            val welcomeConfig = roleConfig.welcomeMessageConfig
                            val welcomeMessage = welcomeConfig.createWelcomeMessage(userId)
                            rooms.postMessage(welcomeMessage, welcomeConfig.channel, listOf(userId))
                        } else {
                            val downgradeConfig = activeMemberConfig.downgradeMessageConfig
                            val previousRoleName = concatRolesToString(activeMemberConfig, rolesToRemove)
                            val downgradeMessage = downgradeConfig.createDowngradeMessage(username, previousRoleName, roleName)
                            rooms.postMessage(downgradeMessage, downgradeConfig.channel)
                        }
                    }
                }
            }
        }

        fun removeAllActiveRolesFromUser(activeMemberConfig: ActiveMemberConfig, userId: UserId) {
            val username = usernameCache.lookup(userId)
            val sServerId = Snowflake(activeMemberConfig.guildId)
            runBlocking {
                val sUserId = Snowflake(userId)
                val userRoles = restClient.guild.getGuildMember(sServerId, sUserId).roles.map { it.value }.toSet()
                val activeRoles = activeMemberConfig.roleConfigs.map { it.roleId }.toSet()
                val userActiveRoles = userRoles.intersect(activeRoles)
                if (userActiveRoles.isEmpty()) {
                    logger.debug { "$username has no active roles to remove" }
                } else {
                    if (userActiveRoles.size > 1) {
                        logger.warn("Expected at most one role to remove while removing all active roles from a user... Removing $userActiveRoles from $userId")
                    }
                    userActiveRoles.forEach { roleId ->
                       removeActiveRole(activeMemberConfig.guildId, roleId, userId)
                    }
                    val removalConfig = activeMemberConfig.removalMessageConfig
                    val previousRoleName = concatRolesToString(activeMemberConfig, userActiveRoles)
                    val message = removalConfig.createRemovalMessage(username, previousRoleName)
                    rooms.postMessage(message, removalConfig.channel)
                }
            }
        }

        /** Remove the role from the user.
         * This function is private, as it likely should not be used directly...
         *  - use [addActiveRole] to transition users between roles
         *  - use [removeAllActiveRolesFromUser] to remove all active roles from a user
         */
        private fun removeActiveRole(guildId: GuildId, roleId: RoleId, userId: UserId) {
            val username = usernameCache.lookup(userId)
            val roleName = roleNameCache.lookup(guildId, roleId)
            val sServerId = Snowflake(guildId)
            val sRoleId = Snowflake(roleId)
            runBlocking {
                val sUserId = Snowflake(userId)
                val userRoles = restClient.guild.getGuildMember(sServerId, sUserId).roles.map { it.value }
                if (roleId !in userRoles) {
                    logger.debug { "$username doesn't have role $roleId ($roleName) to remove" }
                } else {
                    if (dryRun) {
                        logger.info("Dry run... would have removed $roleName from $username")
                    } else {
                        restClient.guild.deleteRoleFromGuildMember(sServerId, sUserId, sRoleId)
                        logger.info("Removed $roleName from $username")
                    }
                }
            }
        }

        /**
         * It's possible multiple roles are being removed from the user if there was some manual intervention... or a bug
         * ...If so, join them together into one string
         */
        private fun concatRolesToString(activeMemberConfig: ActiveMemberConfig, roleIds: Set<RoleId>): String {
            return roleIds.joinToString("+") { removedRoleId ->
                roleNameCache.lookup(activeMemberConfig.guildId, removedRoleId)
            }
        }
    }

    val rooms = _Rooms()
    inner class _Rooms() {
        fun postMessage(message: String, channelId: ChannelId, usersMentioned: List<UserId> = listOf()) {
            if (dryRun) {
                val channelName = channelNameCache.lookup(channelId)
                logger.info { "Dry run... would have posted: \"$message\" in $channelName."}
            } else {
                runBlocking {
                    restClient.channel.createMessage(Snowflake(channelId)) {
                        this.content = message
                        this.allowedMentions = AllowedMentionsBuilder().also { builder ->
                            usersMentioned.forEach { userId ->
                                builder.users.add(Snowflake(userId))
                            }
                        }
                    }
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
                    val userId = this.getUserId()
                    val username = usernameCache.lookup(userId)
                    val localDate = this.getLocalDate()
                    logger.debug { "Message received from $username on $localDate in $channelName" }
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