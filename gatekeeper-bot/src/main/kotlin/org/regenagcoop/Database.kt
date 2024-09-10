package org.regenagcoop

import org.regenagcoop.discord.model.UserId
import org.regenagcoop.model.PostHistory
import java.time.LocalDate

class Database {
    private val postHistory = mutableMapOf<UserId, MutableSet<LocalDate>>()
    private val lock = object { }

    fun overwritePostHistory(postHistory: PostHistory) {
        synchronized(lock) {
            this.postHistory.clear()
            postHistory.forEach { (userId, dates) ->
                this.postHistory[userId] = dates.toMutableSet()
            }
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