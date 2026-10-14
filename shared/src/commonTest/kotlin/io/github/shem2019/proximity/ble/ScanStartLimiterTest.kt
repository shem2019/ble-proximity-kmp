package io.github.shem2019.proximity.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class ScanStartLimiterTest {

    @Test
    fun allowsStartsUpToTheLimit() {
        val limiter = ScanStartLimiter(maxStarts = 5, windowMillis = 30_000)
        repeat(5) { i ->
            assertEquals(0, limiter.delayBeforeStart(i * 1_000L))
            limiter.recordStart(i * 1_000L)
        }
    }

    @Test
    fun theSixthStartWaitsForTheFirstToLeaveTheWindow() {
        val limiter = ScanStartLimiter(maxStarts = 5, windowMillis = 30_000)
        repeat(5) { i -> limiter.recordStart(i * 1_000L) }
        // First start at 0 leaves the window at 30 000; asked at 5 000, wait 25 000.
        assertEquals(25_000, limiter.delayBeforeStart(5_000))
        assertEquals(0, limiter.delayBeforeStart(30_000))
    }

    @Test
    fun startsSpreadOutNeverWait() {
        val limiter = ScanStartLimiter(maxStarts = 5, windowMillis = 30_000)
        repeat(50) { i ->
            val now = i * 7_000L
            assertEquals(0, limiter.delayBeforeStart(now))
            limiter.recordStart(now)
        }
    }
}
