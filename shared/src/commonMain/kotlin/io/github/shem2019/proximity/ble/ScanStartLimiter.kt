package io.github.shem2019.proximity.ble

/**
 * Keeps scan starts under the platform's rate limit.
 *
 * Android (7.0 and later) lets an app start at most five scans in any 30 seconds. A sixth
 * start fails silently: no error and no results. The limiter remembers recent starts and
 * says how long to wait before the next one is safe.
 */
class ScanStartLimiter(
    private val maxStarts: Int = 5,
    private val windowMillis: Long = 30_000,
) {
    private val starts = ArrayDeque<Long>()

    init {
        require(maxStarts >= 1) { "maxStarts must be at least 1" }
        require(windowMillis > 0) { "windowMillis must be positive" }
    }

    /** Milliseconds to wait before a start at [nowMillis] stays within the limit. */
    fun delayBeforeStart(nowMillis: Long): Long {
        dropExpired(nowMillis)
        if (starts.size < maxStarts) return 0
        return starts.first() + windowMillis - nowMillis
    }

    /** Records a start that happened at [nowMillis]. */
    fun recordStart(nowMillis: Long) {
        dropExpired(nowMillis)
        starts.addLast(nowMillis)
    }

    private fun dropExpired(nowMillis: Long) {
        while (starts.isNotEmpty() && nowMillis - starts.first() >= windowMillis) starts.removeFirst()
    }
}
