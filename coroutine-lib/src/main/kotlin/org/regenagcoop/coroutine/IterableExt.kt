package org.regenagcoop.coroutine

import kotlinx.coroutines.*

/**
 * Map over the iterable in parallel by launching a new coroutine in the IO threadpool for every element
 * Based on: https://stackoverflow.com/a/58735169
 */
suspend fun <A, B> Iterable<A>.parallelMapIO(transform: suspend (A) -> B): List<B> {
    val collection = this
    return coroutineScope {
        withContext(Dispatchers.IO) {
            collection.map {
                async {
                    transform(it)
                }
            }.awaitAll()
        }
    }
}

suspend fun <A> Iterable<A>.parallelForEachIO(onEach: suspend (A) -> Unit) {
    val collection = this
    collection.parallelMapIO(onEach)
}

suspend fun <A> Iterable<A>.parallelFilterIO(check: suspend (A) -> Boolean): List<A> {
    val collection = this
    val shouldKeep = collection.parallelMapIO(check)
    return collection.filterIndexed { idx, _ ->
        shouldKeep[idx]
    }
}