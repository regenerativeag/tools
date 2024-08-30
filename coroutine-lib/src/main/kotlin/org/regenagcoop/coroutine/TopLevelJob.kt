package org.regenagcoop.coroutine

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlin.system.exitProcess

open class TopLevelJob(
    val name: String,
    val dependencies: List<TopLevelJob> = listOf(),
    val execute: suspend () -> Unit,
) {
    private val logger: KLogger = KotlinLogging.logger { }

    @OptIn(DelicateCoroutinesApi::class)
    private val coroutineJob: Job by lazy {
        GlobalScope.launch(uncaughtExceptionHandler) {
            try {
                waitForDependencies()
            } catch(e: Throwable) {
                logger.error { "TopLevelJob '$name' refusing to start due to failed dependency" }
                throw e
            }
            logger.debug { "Starting TopLevelJob '$name'" }
            try {
                execute()
            } catch (e: Throwable) {
                logger.warn { "TopLevelJob '$name' failed" }
                throw e
            }
        }
    }

    open val uncaughtExceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, exception ->
        logger.error(exception) { "Uncaught exception in TopLevelJob: $name" }
        suppressedExceptionsHandler(exception.suppressedExceptions)
    }

    open fun suppressedExceptionsHandler(suppressedExceptions: List<Throwable>) {
        suppressedExceptions.forEach { suppressedException ->
            logger.error(suppressedException) { "Suppressed exception in TopLevelJob: $name" }
            suppressedExceptionsHandler(suppressedException.suppressedExceptions) // recurse
        }
    }

    init {
        launch()
    }


    suspend fun waitUntilSuccess() {
        coroutineJob.join()
        if (coroutineJob.isCancelled) {
            throw IllegalStateException("TopLevelJob '$name' completed unsuccessfully")
        }
    }

    private fun launch() {
        coroutineJob // just accessing the lazy variable will create and start the job
    }

    private suspend fun waitForDependencies() {
        try {
            dependencies.forEach {
                it.waitUntilSuccess()
            }
        } catch(cause: Throwable) {
            throw IllegalStateException("At least one dependency failed", cause)
        }
    }

    companion object {
        private val staticLogger: KLogger = KotlinLogging.logger { }

        fun awaitEndlessJobs(vararg topLevelJobs: TopLevelJob) {
            // If any indefinite job fails, kill the application (the whole application should be debugged/restarted)
            topLevelJobs.forEach { topLevelJob ->
                topLevelJob.coroutineJob.invokeOnCompletion {
                    staticLogger.warn { "Exiting application because endless TopLevelJob '${topLevelJob.name}' terminated" }
                    exitProcess(-1)
                }
            }
            // Block on the indefinite jobs to complete (never, unless an exception is unhandled or the user sends SIGINT/SIGKILL).
            runBlocking {
                topLevelJobs.forEach { it.waitUntilSuccess() }
            }
        }

        fun createTopLevelJob(
            name: String,
            dependencies: List<TopLevelJob> = listOf(),
            job: suspend () -> Unit
        ) = TopLevelJob(name, dependencies,  job)
    }
}