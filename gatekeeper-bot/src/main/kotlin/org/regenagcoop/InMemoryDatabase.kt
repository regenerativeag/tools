package org.regenagcoop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.regenagcoop.Database.Companion.AddPostResult
import org.regenagcoop.Database.Companion.AddReactionResult
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActivityHistory
import org.regenagcoop.model.RoleChange
import java.time.LocalDate

class InMemoryDatabase(initialData: ActivityHistory) {
    private val logger = KotlinLogging.logger {  }

    private val mutex = Mutex()
    // TODO #29: These maps currently grow in memory until the application restarts. They need to be pruned periodically to handle servers that have tons of activity.
    private val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
    private val reactionHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
    private val roleChangeHistory = mutableMapOf<UserId, MutableList<RoleChange>>()

    init {
        logger.debug { "Initializing in-memory DB: $initialData" }

        initialData.postHistory.forEach { (userId, dates) ->
            postHistory[userId] = dates.toMutableSet()
        }

        initialData.reactionHistory.forEach { (userId, dates) ->
            reactionHistory[userId] = dates.toMutableSet()
        }

        initialData.roleChangeHistory.forEach { roleChange ->
            if (roleChange.userId !in roleChangeHistory) {
                roleChangeHistory[roleChange.userId] = mutableListOf()
            }
            roleChangeHistory[roleChange.userId]!!.add(roleChange)
        }
    }

    suspend fun addPost(userId: UserId, date: LocalDate): AddPostResult {
        mutex.withLock {
            if (userId !in postHistory) {
                postHistory[userId] = mutableSetOf()
            }
            val postDates = postHistory[userId]!!
            val firstPostOfDay = date !in postDates
            if (firstPostOfDay) {
                postDates.add(date)
            }
            return AddPostResult(firstPostOfDay, postDates.toSet())
        }
    }

    suspend fun addReaction(userId: UserId, date: LocalDate): AddReactionResult {
        mutex.withLock {
            if (userId !in reactionHistory) {
                reactionHistory[userId] = mutableSetOf()
            }
            val reactionDays = reactionHistory[userId]!!
            val firstReactionOfDay = date !in reactionDays
            if (firstReactionOfDay) {
                reactionDays.add(date)
            }
            return AddReactionResult(firstReactionOfDay)
        }
    }

    suspend fun addRoleChange(roleChange: RoleChange) {
        mutex.withLock {
            if (roleChange.userId !in roleChangeHistory) {
                roleChangeHistory[roleChange.userId] = mutableListOf()
            }
            roleChangeHistory[roleChange.userId]!!.add(roleChange)
        }
    }

    suspend fun getPostHistory(): Map<UserId, Set<LocalDate>> {
        mutex.withLock {
            return postHistory.toMap()
        }
    }

    suspend fun getUsersWhoPostedOnDay(date: LocalDate): Set<UserId> {
        mutex.withLock {
            val posters = mutableSetOf<UserId>()
            postHistory.forEach { (userId, postDates) ->
                if (date in postDates) {
                    posters.add(userId)
                }
            }
            return posters
        }
    }
}