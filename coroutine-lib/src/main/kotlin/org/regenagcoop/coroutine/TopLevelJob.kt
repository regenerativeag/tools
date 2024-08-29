package org.regenagcoop.coroutine

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*

class TopLevelJob(
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
            try {
                execute()
            } catch (e: Throwable) {
                logger.warn { "TopLevelJob '$name' failed" }
                throw e
            }
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

    open val uncaughtExceptionHandler: CoroutineExceptionHandler =  CoroutineExceptionHandler { _, exception ->
        logger.error(exception) { "Uncaught exception in TopLevelJob: $name" }
        suppressedExceptionsHandler(exception.suppressedExceptions)
    }

    open fun suppressedExceptionsHandler(suppressedExceptions: List<Throwable>) {
        suppressedExceptions.forEach { suppressedException ->
            logger.error(suppressedException) { "Suppressed exception in TopLevelJob: $name" }
            suppressedExceptionsHandler(suppressedException.suppressedExceptions) // recurse
        }
    }

    private fun launch() {
        coroutineJob.isCancelled // just accessing the lazy variable will create and start the job
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
        fun awaitIndefiniteJobs(vararg topLevelJobs: TopLevelJob) {
            // Note: these jobs are not restarted if they fail...
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