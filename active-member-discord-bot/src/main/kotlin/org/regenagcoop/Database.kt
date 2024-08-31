package org.regenagcoop

import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.ActivityHistory
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

class Database {
    private val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
    private val reactionHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
    private var initialized: Boolean = false

    // TODO #16: Use mutex instead of synchronous block
    private val lock = object { }

    fun initialize(activityHistory: ActivityHistory) {
        synchronized(lock) {
            if (initialized) {
                throw IllegalStateException("database already initialized")
            }
            activityHistory.postHistory.forEach { (userId, dates) ->
                postHistory[userId] = dates.toMutableSet()
            }
            activityHistory.reactionHistory.forEach { (userId, dates) ->
                reactionHistory[userId] = dates.toMutableSet()
            }
            initialized = true
        }
    }

    fun addPost(userId: UserId, date: LocalDate): AddPostResult {
        synchronized(lock) {
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

    fun getPostHistory(): Map<UserId, Set<LocalDate>> {
        synchronized(lock) {
            return postHistory.toMap()
        }
    }

    companion object {
        data class AddPostResult(val isFirstPostOfDay: Boolean, val postDays: Set<LocalDate>)
    }
}