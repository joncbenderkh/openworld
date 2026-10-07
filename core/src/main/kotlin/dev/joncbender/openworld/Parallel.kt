package dev.joncbender.openworld

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Splits an index range across a fixed pool of worker threads, for the
 * per-tile passes (noise, dual construction, cache I/O) whose iterations are
 * independent of each other. Plain executors rather than java.util.stream or
 * coroutines: the former needs API 24 and the project's minSdk is 23, the
 * latter isn't a dependency here.
 *
 * Not re-entrant: [forRanges] blocks until every chunk finishes, so calling
 * it from inside a chunk could starve the pool.
 */
object Parallel {
    // Below this many items per chunk, handing work to another thread costs
    // more than the work itself.
    private const val MIN_CHUNK = 2048

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private val pool = Executors.newFixedThreadPool(workers) { runnable ->
        Thread(runnable, "openworld-parallel").apply { isDaemon = true }
    }

    /** Calls [body] with disjoint `[from, to)` chunks that together cover `0 until count`. */
    fun forRanges(count: Int, body: (from: Int, to: Int) -> Unit) {
        val chunks = minOf(workers, (count + MIN_CHUNK - 1) / MIN_CHUNK).coerceAtLeast(1)
        if (chunks == 1) {
            body(0, count)
            return
        }
        val step = (count + chunks - 1) / chunks
        val futures = (0 until chunks).map { c ->
            val from = c * step
            val to = minOf(count, from + step)
            pool.submit(Callable { body(from, to) })
        }
        try {
            futures.forEach { it.get() }
        } catch (e: ExecutionException) {
            futures.forEach { it.cancel(true) }
            throw e.cause ?: e
        }
    }

    /** Calls [body] once per index in `0 until count`, spread across the pool. */
    inline fun forEachIndex(count: Int, crossinline body: (Int) -> Unit) {
        forRanges(count) { from, to ->
            for (i in from until to) body(i)
        }
    }
}
