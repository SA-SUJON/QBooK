package org.qbook.utils

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V4 Step 5 — Production-grade centralized executors.
 *
 * Design goals (verified through code audit):
 * - Named threads for production debugging
 * - Proper rejection policy (never silently drop or crash unexpectedly)
 * - Uncaught exception handling (never lose errors silently)
 * - Clear separation of concerns
 * - Support for graceful shutdown (called from Application)
 *
 * Pools:
 * - background     : Light, short-lived background work
 * - heavyBackground: CPU/network heavy work (prefetch, sync, etc.)
 * - diskIO         : Serialized disk operations
 */
object AppExecutors {

    private const val TAG = "AppExecutors"

    // ============================================================
    // Thread Factory — gives us named, traceable threads
    // ============================================================
    private fun createThreadFactory(poolName: String): ThreadFactory {
        val threadNumber = AtomicInteger(1)
        return ThreadFactory { runnable ->
            Thread(runnable, "$poolName-${threadNumber.getAndIncrement()}").apply {
                isDaemon = false
                priority = Thread.NORM_PRIORITY
                // Capture uncaught exceptions globally for this pool
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
                    Log.e(TAG, "Uncaught exception in ${t.name}", e)
                    // In production we could also report to crash reporting here
                }
            }
        }
    }

    // ============================================================
    // Rejection Policy
    // CallerRunsPolicy is safe: the caller (usually background work)
    // will run the task itself instead of dropping it or throwing.
    // ============================================================
    private val safeRejectionHandler: RejectedExecutionHandler =
        ThreadPoolExecutor.CallerRunsPolicy()

    // ============================================================
    // background — light, frequent, non-blocking work
    // ============================================================
    // Java constructors take positional arguments only - naming them here is
    // what made this file fail to compile.
    val background: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            2,                                   // corePoolSize
            4,                                   // maximumPoolSize
            30L,                                 // keepAliveTime
            TimeUnit.SECONDS,
            LinkedBlockingQueue(64),
            createThreadFactory("QBooK-Bg"),
            safeRejectionHandler
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    // ============================================================
    // heavyBackground — prefetch, offline sync, expensive work
    // ============================================================
    val heavyBackground: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1,                                   // corePoolSize
            3,                                   // maximumPoolSize
            60L,                                 // keepAliveTime
            TimeUnit.SECONDS,
            LinkedBlockingQueue(48),
            createThreadFactory("QBooK-Heavy"),
            safeRejectionHandler
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    // ============================================================
    // diskIO — single threaded, serialized disk access
    // ============================================================
    val diskIO: java.util.concurrent.ExecutorService by lazy {
        Executors.newSingleThreadExecutor(
            createThreadFactory("QBooK-DiskIO")
        )
    }

    // ============================================================
    // Convenience methods (optional, for readability)
    // ============================================================
    fun executeBackground(task: () -> Unit) {
        background.execute(wrapWithLogging(task))
    }

    fun executeHeavy(task: () -> Unit) {
        heavyBackground.execute(wrapWithLogging(task))
    }

    fun executeDisk(task: () -> Unit) {
        diskIO.execute(wrapWithLogging(task))
    }

    // Wraps every task so uncaught exceptions are always logged
    private fun wrapWithLogging(task: () -> Unit): Runnable {
        return Runnable {
            try {
                task()
            } catch (t: Throwable) {
                Log.e(TAG, "Task failed", t)
            }
        }
    }

    // ============================================================
    // Shutdown (called from Application when possible)
    // ============================================================
    fun shutdown() {
        try {
            background.shutdown()
            heavyBackground.shutdown()
            diskIO.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Shutdown encountered exception", e)
        }
    }

    fun shutdownNow() {
        try {
            background.shutdownNow()
            heavyBackground.shutdownNow()
            diskIO.shutdownNow()
        } catch (e: Exception) {
            Log.w(TAG, "Immediate shutdown encountered exception", e)
        }
    }
}