package org.regenagcoop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActivityHistory
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

class Database {
    private val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
    private val reactionHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
    private var initialized: Boolean = false

    private val mutex = Mutex()


    suspend fun initialize(activityHistory: ActivityHistory) {
        mutex.withLock {
            if (initialized) {
                throw IllegalStateException("database already initialized")
            }
            initialized = true
            activityHistory.postHistory.forEach { (userId, dates) ->
                postHistory[userId] = dates.toMutableSet()
            }
            activityHistory.reactionHistory.forEach { (userId, dates) ->
                reactionHistory[userId] = dates.toMutableSet()
            }
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

    suspend fun getPostHistory(): Map<UserId, Set<LocalDate>> {
        mutex.withLock {
            return postHistory.toMap()
        }
    }

    companion object {
        data class AddPostResult(val isFirstPostOfDay: Boolean, val postDays: Set<LocalDate>)
    }
}