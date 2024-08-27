package org.regenagcoop.coroutine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Map over an iterable in parallel, and return results.
 * Based on: https://stackoverflow.com/a/58735169
 */
suspend fun <A, B> Iterable<A>.parallelMap(transform: suspend (A) -> B): List<B> {
    val collection = this
    return coroutineScope {
        collection.map {
            async { transform(it) }
        }.awaitAll()
    }
}

suspend fun <A> Iterable<A>.parallelForEach(onEach: suspend (A) -> Unit) {
    val collection = this
    collection.parallelMap(onEach)
}

suspend fun <A> Iterable<A>.parallelFilter(check: suspend (A) -> Boolean): List<A> {
    val collection = this
    val shouldKeep = collection.parallelMap(check)
    return collection.filterIndexed { idx, _ ->
        shouldKeep[idx]
    }
}