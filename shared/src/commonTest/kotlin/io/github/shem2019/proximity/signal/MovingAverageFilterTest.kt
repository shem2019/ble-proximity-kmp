package io.github.shem2019.proximity.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MovingAverageFilterTest {

    @Test
    fun averagesWhatItHasUntilTheWindowFills() {
        val filter = MovingAverageFilter(windowSize = 3)
        assertEquals(-60.0, filter.update(-60.0, 0))
        assertEquals(-65.0, filter.update(-70.0, 0))
        assertEquals(-70.0, filter.update(-80.0, 0))
    }

    @Test
    fun dropsTheOldestSampleOnceFull() {
        val filter = MovingAverageFilter(windowSize = 3)
        listOf(-60.0, -70.0, -80.0).forEach { filter.update(it, 0) }
        // (-70 - 80 - 90) / 3
        assertEquals(-80.0, filter.update(-90.0, 0))
    }

    @Test
    fun windowOfOnePassesSamplesThrough() {
        val filter = MovingAverageFilter(windowSize = 1)
        assertEquals(-60.0, filter.update(-60.0, 0))
        assertEquals(-75.0, filter.update(-75.0, 0))
    }

    @Test
    fun resetForgetsHistory() {
        val filter = MovingAverageFilter(windowSize = 4)
        listOf(-90.0, -90.0, -90.0).forEach { filter.update(it, 0) }
        filter.reset()
        assertEquals(-50.0, filter.update(-50.0, 0))
    }

    @Test
    fun reducesNoiseBySquareRootOfWindow() {
        // Averaging 8 independent samples divides the standard deviation by √8: 4 dB → 1.41 dB.
        val noise = GaussianNoise(seed = 7, sigma = 4.0)
        val filter = MovingAverageFilter(windowSize = 8)
        val smoothed = List(2_000) { i -> filter.update(-70 + noise.next(), i * 100L) }.drop(8)
        assertTrue(standardDeviation(smoothed) < 1.7, "smoothed σ = ${standardDeviation(smoothed)}")
    }

    @Test
    fun followsAStepWithinOneWindow() {
        val filter = MovingAverageFilter(windowSize = 8)
        repeat(8) { filter.update(-70.0, 0) }
        // k packets into a 10 dB step the mean has moved 10k/8 dB: 7 → 8.75 dB, 8 → 10 dB.
        repeat(6) { filter.update(-80.0, 0) }
        assertEquals(-78.75, filter.update(-80.0, 0))
        assertEquals(-80.0, filter.update(-80.0, 0))
    }

    @Test
    fun rejectsEmptyWindow() {
        assertFailsWith<IllegalArgumentException> { MovingAverageFilter(windowSize = 0) }
    }
}
