package com.dustbook.app.utils

/**
 * A running record of every layout event, so two runs can be compared.
 *
 * The still snapshot [LayoutProbe] takes answers "what is true now". It cannot
 * answer "what changed between the run that looked right and the run that did
 * not", because by the time anyone looks the moment has passed. A reel that is
 * sometimes centred and sometimes high, on one unchanged device, is by
 * definition a difference in *sequence* rather than in geometry - so the
 * sequence is what has to be captured.
 *
 * Entries are appended from the page (via the JS reporter) and from the app,
 * both stamped from the same clock, so the ordering between the two sides is
 * meaningful. The buffer is small and fixed: this is a diagnostic, and it must
 * not grow without bound or cost anything measurable while it is off.
 */
object LayoutTrace {

    /*
     * Round two logs far more: every scrollTop write, every focus, every
     * scrollIntoView and a per-second player census. 400 lines was a few
     * seconds of that, and the interesting moment would have scrolled out of
     * the buffer before it could be read.
     */
    private const val MAX_LINES = 1200

    /** Off unless the user turns the diagnostic on. Checked on every call. */
    @Volatile
    var enabled: Boolean = false

    private val lines = ArrayList<String>(MAX_LINES)
    private var origin = 0L

    @Synchronized
    fun reset() {
        lines.clear()
        origin = System.currentTimeMillis()
    }

    /** Milliseconds since the trace was reset, so runs line up for comparison. */
    @Synchronized
    private fun stamp(): Long {
        if (origin == 0L) origin = System.currentTimeMillis()
        return System.currentTimeMillis() - origin
    }

    @Synchronized
    fun add(source: String, event: String) {
        if (!enabled) return
        if (lines.size >= MAX_LINES) lines.removeAt(0)
        lines.add("%6d %-4s %s".format(stamp(), source, event))
    }

    /** From the page. Kept separate so JS-side events are obvious in the log. */
    fun page(event: String) = add("js", event)

    /** From the app. */
    fun app(event: String) = add("app", event)

    @Synchronized
    fun dump(): String =
        if (lines.isEmpty()) "(nothing recorded - is the diagnostic on?)"
        else lines.joinToString("\n")

    @Synchronized
    fun count(): Int = lines.size
}
