package org.regenagcoop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.regenagcoop.discord.Discord
import org.regenagcoop.discord.model.Message
import org.regenagcoop.discord.model.UserId
import org.regenagcoop.discord.service.*
import org.regenagcoop.model.ActiveMemberConfig
import org.regenagcoop.model.ActivityHistory
import org.regenagcoop.model.RoleChange
import java.time.LocalDate

/**
 * High-level interface for interacting with the in-memory database & persistence log.
 *
 * The persistence log is read during [initialize], and any missing post history is scanned for.
 *
 * Posts are persisted to the persistence log when the caller invokes [persistYesterdaysPostHistory].
 *
 * Reactions are persisted to the persistence log every time [addReaction] is called.
 *
 * RoleChanges are persisted to the persistence log every time [addRoleChange] is called.
 *
 */
class Database(
    private val discord: Discord,
    private val activeMemberConfig: ActiveMemberConfig,
) {
    private val logger = KotlinLogging.logger {  }

    private val mutex = Mutex()
    private var initialized: Boolean = false
    private var startupDate: LocalDate? = null
    private var inMemoryDatabase: InMemoryDatabase? = null

    private val persistedActivityService = PersistedActivityService(discord, activeMemberConfig)
    private val persistPostsService = PersistPostsService(discord, activeMemberConfig)
    private var persistReactionService: PersistReactionService? = null // cannot be initialized until persisted history is fetched
    private val persistRoleChangeService = PersistRoleChangeService(discord, activeMemberConfig)
    private val scanActivityService = ScanActivityService(discord, persistedActivityService)

    suspend fun initialize(startupDate: LocalDate) {
        mutex.withLock {
            if (initialized) {
                throw IllegalStateException("database already initialized")
            }

            this.initialized = true
            this.startupDate = startupDate

            val (activityHistory, persistedDates, persistedHistoryMessages) = fetchActivityHistory()

            this.persistReactionService = PersistReactionService(discord, activeMemberConfig, startupDate, persistedHistoryMessages)

            persistMissingPostHistory(activityHistory, persistedDates)

            this.inMemoryDatabase = InMemoryDatabase(activityHistory)
        }
    }

    /** Call this function once per day, at the beginning of the day, to persist yesterday's post history */
    suspend fun persistYesterdaysPostHistory(yesterday: LocalDate) {
        mutex.withLock {
            ensureInitialized()
            val posters = inMemoryDatabase!!.getUsersWhoPostedOnDay(yesterday)
            logger.debug { "Persisting yesterday's ($yesterday) post history: ${posters.sorted()}" }
            persistPostsService.persistPostHistoryForDay(yesterday, posters)
        }
    }

    suspend fun addPost(userId: UserId, date: LocalDate): AddPostResult {
        mutex.withLock {
            ensureInitialized()
            return inMemoryDatabase!!.addPost(userId, date)
        }
    }

    suspend fun addReaction(userId: UserId, date: LocalDate) {
        mutex.withLock {
            ensureInitialized()
            val (isFirstReactionOfDay) = inMemoryDatabase!!.addReaction(userId, date)
            if (isFirstReactionOfDay) {
                persistReactionService!!.persistReaction(date, userId)
            }
        }
    }

    suspend fun addRoleChange(roleChange: RoleChange) {
        mutex.withLock {
            ensureInitialized()
            inMemoryDatabase!!.addRoleChange(roleChange)
            persistRoleChangeService.persistRoleChange(roleChange)
        }
    }

    suspend fun getPostHistory(): Map<UserId, Set<LocalDate>> {
        mutex.withLock {
            ensureInitialized()
            return inMemoryDatabase!!.getPostHistory()
        }
    }

    /**
     * Must be called within [mutex]
     *
     * Reasoning for internal instead of private: for tests to override and simplify with mock data.
     */
    internal suspend fun fetchActivityHistory(): Triple<ActivityHistory, Set<LocalDate>, List<Message>> {
        logger.debug { "Reading from persistence log" }
        val persistedHistoryMessages = persistedActivityService.fetchPersistedHistoryMessages()

        logger.debug { "Scanning for missing post history" }
        val (activityHistory, persistedDates) = scanActivityService.scanForCompleteActivityHistory(
            startupDate!!,
            persistedHistoryMessages
        )

        return Triple(activityHistory, persistedDates, persistedHistoryMessages)
    }

    /**
     * Must be called within [mutex]
     *
     * Reasoning for internal instead of private: for tests to override and simplify with mock data.
     */
    internal suspend fun persistMissingPostHistory(activityHistory: ActivityHistory, persistedDates: Set<LocalDate>) {
        logger.debug { "Persisting missing post history into persistence channel" }
        persistPostsService.persistMissingPostHistory(
            startupDate!!,
            activityHistory.postHistory,
            persistedDates
        )
    }

    private fun ensureInitialized() {
        if (!initialized) {
            throw IllegalStateException("Please call initialize() before calling this method.")
        }
    }

    companion object {
        data class AddPostResult(val isFirstPostOfDay: Boolean, val postDays: Set<LocalDate>)
        data class AddReactionResult(val isFirstReactionOfDay: Boolean)
    }
}