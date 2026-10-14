package io.github.shem2019.proximity.signal

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Packets 100 ms apart unless a test says otherwise. With q = 5 dBm²/s that is a drift of
 * 0.5 dBm² per packet, and r = 16 dBm².
 */
class KalmanFilterTest {

    private class Feed(val filter: KalmanFilter = KalmanFilter(), var now: Long = 0) {
        fun next(rssi: Double, afterMillis: Long = 100): Double {
            now += afterMillis
            return filter.update(rssi, now)
        }

        fun settleAt(rssi: Double) = repeat(200) { next(rssi) }
    }

    @Test
    fun firstSampleIsTakenAsIs() {
        assertEquals(-64.0, KalmanFilter().update(-64.0, 0))
    }

    @Test
    fun secondSampleFollowsTheTextbookUpdate() {
        // p = r = 16 after the first sample. Predict: p = 16 + 0.5 = 16.5.
        // Gain k = 16.5 / 32.5 = 0.50769. Estimate = -60 + k × (-70 − -60) = -65.0769.
        val feed = Feed()
        feed.next(-60.0)
        assertEquals(-65.0769, feed.next(-70.0), absoluteTolerance = 1e-4)
    }

    @Test
    fun aSingleDeepFadeBarelyMovesTheEstimate() {
        // Steady state: the predicted covariance P solves P² − qP − qr = 0, so
        // P = (0.5 + √(0.25 + 32)) / 2 = 3.0895 and k = P / (P + r) = 0.1618.
        // A 10 dB fade moves the estimate by k × 10 = 1.618 dB.
        val feed = Feed()
        feed.settleAt(-70.0)
        assertEquals(-71.618, feed.next(-80.0), absoluteTolerance = 1e-3)
    }

    @Test
    fun aSustainedChangeComesThrough() {
        // After n packets at the new level the remaining error is 10 dB × (1 − k)^n:
        // 10 × 0.8382^13 = 1.007 dB and 10 × 0.8382^14 = 0.844 dB, so 14 packets to get
        // within 1 dB of -80.
        val feed = Feed()
        feed.settleAt(-70.0)
        repeat(12) { feed.next(-80.0) }
        assertEquals(1.007, abs(feed.next(-80.0) - -80.0), absoluteTolerance = 1e-3)
        assertEquals(0.844, abs(feed.next(-80.0) - -80.0), absoluteTolerance = 1e-3)
    }

    @Test
    fun aPacketAfterALongGapCountsForMore() {
        // Steady posterior p = P × (1 − k) = 3.0895 × 0.8382 = 2.5897.
        // A 5 s gap adds 5 × 5 = 25: p = 27.5897, k = 27.5897 / 43.5897 = 0.6329.
        // A 10 dB step then moves the estimate 6.33 dB, against 1.62 dB after 100 ms.
        val feed = Feed()
        feed.settleAt(-70.0)
        assertEquals(-76.329, feed.next(-80.0, afterMillis = 5_000), absoluteTolerance = 1e-3)
    }

    @Test
    fun packetsAtTheSameInstantAddNoDrift() {
        // With no elapsed time the filter averages: p goes 16 → 8 → 5.33, k = 1/2 then 1/3.
        val filter = KalmanFilter()
        filter.update(-60.0, 1_000)
        assertEquals(-65.0, filter.update(-70.0, 1_000), absoluteTolerance = 1e-9)
        assertEquals(-66.6667, filter.update(-70.0, 1_000), absoluteTolerance = 1e-4)
    }

    @Test
    fun reducesNoiseOnAStillSignal() {
        // In steady state the filter is an exponential smoother with α = k, whose output
        // variance is σ² × k / (2 − k): 16 × 0.1618 / 1.8382 = 1.41, so σ ≈ 1.19 dB.
        val noise = GaussianNoise(seed = 42, sigma = 4.0)
        val raw = List(4_000) { -70 + noise.next() }
        val feed = Feed()
        val smoothed = raw.map { feed.next(it) }.drop(100)
        assertTrue(standardDeviation(raw) > 3.7, "raw σ = ${standardDeviation(raw)}")
        assertTrue(standardDeviation(smoothed) < 1.4, "smoothed σ = ${standardDeviation(smoothed)}")
        assertEquals(-70.0, smoothed.average(), absoluteTolerance = 0.3)
    }

    @Test
    fun resetStartsFromTheNextSample() {
        val feed = Feed()
        repeat(10) { feed.next(-90.0) }
        feed.filter.reset()
        assertEquals(-50.0, feed.next(-50.0))
    }

    @Test
    fun rejectsNonPositiveNoise() {
        assertFailsWith<IllegalArgumentException> { KalmanFilter(processNoisePerSecond = 0.0) }
        assertFailsWith<IllegalArgumentException> { KalmanFilter(measurementNoise = -1.0) }
    }
}
