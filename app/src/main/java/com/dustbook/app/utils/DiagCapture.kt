package com.dustbook.app.utils

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

/**
 * Singleton entry point for writing diagnostic entries from
 * anywhere in the app. The store is created lazily on the
 * first call so the cost is a single volatile read at app
 * start.
 *
 * The hot path is one of three branches in order of cost:
 *   1. Channel disabled. The first call in [write] reads the
 *      channel's enabled flag and returns. No allocation, no
 *      lock, no file IO. This is the path the production user
 *      takes on every call.
 *   2. Channel enabled, no current mode. The call still
 *      returns without writing - the only writes that land
 *      are the ones a developer has explicitly turned on
 *      while investigating a bug.
 *   3. Channel enabled, mode known. The call goes to the
 *      store. Cost is one ReentrantLock per channel and one
 *      file append, both cheap.
 *
 * The current [mode] is a separate flag the app sets on every
 * network change. Offline mode is detected by [NetworkPolicy];
 * [setMode] is called from the same place. The flag is
 * read on every [write] so a mode change is reflected on the
 * next entry without restarting the app.
 */
object DiagCapture {

    @Volatile private var store: DiagnosticStore? = null
    @Volatile private var currentMode: Diag.Mode = Diag.Mode.ONLINE
    private val sessionId: String = UUID.randomUUID().toString()
    @Volatile private var crashHandlerInstalled = false

    /** Set the current mode. Called from the network policy
     *  listener in MainActivity so an online-to-offline
     *  transition is reflected in the next entry without
     *  any explicit capture point needing to know. */
    fun setMode(mode: Diag.Mode) { currentMode = mode }

    /** Read the current mode. Mostly for tests and the
     *  developer-options screen. */
    fun getMode(): Diag.Mode = currentMode

    /** Write one entry to a channel. Returns immediately if
     *  the channel is disabled or the store has not been
     *  initialised yet (which is the case for the first
     *  handful of calls in the process before [init] has
     *  been called from MainActivity.onCreate). */
    fun write(
        ctx: Context,
        channel: Diag.Channel,
        level: Diag.Level = Diag.Level.INFO,
        message: String
    ) {
        val s = store ?: init(ctx).also { store = it }
        s.write(channel, Diag(
            ts = System.currentTimeMillis(),
            mode = currentMode,
            channel = channel,
            level = level,
            message = message.take(MAX_MESSAGE_CHARS),
            sessionId = sessionId,
            thread = Thread.currentThread().name
        ))
    }

    /** Record an exception with its complete causal chain without ever
     * allowing diagnostics to crash the app. */
    fun writeException(ctx: Context, channel: Diag.Channel, message: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        write(ctx, channel, Diag.Level.ERROR, "$message\n${sw}".take(MAX_MESSAGE_CHARS))
    }

    /** A human-readable marker lets a log prove exactly when the user
     * pressed "reproduce" instead of relying on approximate timestamps. */
    fun mark(ctx: Context, message: String = "USER_MARK") {
        write(ctx, Diag.Channel.APP_LIFECYCLE, Diag.Level.WARN, message)
    }
    }

    /** One-shot initialisation from MainActivity.onCreate.
     *  Idempotent. Safe to call more than once. */
    fun init(ctx: Context): DiagnosticStore {
        val existing = store
        if (existing != null) return existing
        val created = DiagnosticStore(ctx.applicationContext)
        store = created
        if (!crashHandlerInstalled) {
            synchronized(this) {
                if (!crashHandlerInstalled) {
                    val previous = Thread.getDefaultUncaughtExceptionHandler()
                    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                        // Best effort: the process may be dying, so never throw here.
                        try {
                            writeException(ctx.applicationContext, Diag.Channel.APP_LIFECYCLE,
                                "uncaught_exception thread=${thread.name}", error)
                        } catch (_: Throwable) { }
                        previous?.uncaughtException(thread, error)
                    }
                    crashHandlerInstalled = true
                }
            }
        }
        return created
    }

    private const val MAX_MESSAGE_CHARS = 12_000

    /** Read every entry in a channel. Used by the viewer and
     *  the export. Returns an empty list if the store is
     *  uninitialised - the developer-options screen treats
     *  that as "no entries yet". */
    fun read(ctx: Context, channel: Diag.Channel): List<Diag> {
        val s = store ?: return emptyList()
        return s.read(channel)
    }

    /** Drop a channel's file. */
    fun clear(ctx: Context, channel: Diag.Channel) {
        val s = store ?: return
        s.clear(channel)
    }

    /** Drop every channel. */
    fun clearAll(ctx: Context) {
        val s = store ?: return
        s.clearAll()
    }

    /** Sum of bytes on disk across all channels. */
    fun totalBytes(ctx: Context): Long {
        val s = store ?: return 0L
        return s.totalBytes()
    }
}
