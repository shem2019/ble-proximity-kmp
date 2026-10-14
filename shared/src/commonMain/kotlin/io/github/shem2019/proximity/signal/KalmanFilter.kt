package io.github.shem2019.proximity.signal

/**
 * One-dimensional Kalman filter for RSSI that models the true signal level as a random walk
 * in time.
 *
 * - [processNoisePerSecond] (q, dBm² per second): how far the true level may drift per
 *   second. Raise it when people walk around; lower it for a phone lying on a desk.
 * - [measurementNoise] (r, dBm²): variance of a single reading. Indoor BLE readings scatter
 *   by about 4 dB, so the default is 4² = 16.
 *
 * Drift is charged per second, not per packet, because BLE packets arrive irregularly:
 * advertising intervals run from 20 ms to 10 s and scans miss packets. After a long gap the
 * estimate is less certain, so the next packet counts for more.
 *
 * The gain k = p / (p + r) weighs each reading by how uncertain the estimate is. At ten
 * packets a second the defaults settle at k = 0.16: one 10 dB fade moves the estimate by
 * 1.6 dB, and a real 10 dB change is followed to within 1 dB in 14 packets.
 */
class KalmanFilter(
    private val processNoisePerSecond: Double = 5.0,
    private val measurementNoise: Double = 16.0,
) : RssiFilter {
    private var estimate = 0.0
    private var errorCovariance = 0.0
    private var lastTimestampMillis = 0L
    private var initialised = false

    init {
        require(processNoisePerSecond > 0) { "processNoisePerSecond must be positive" }
        require(measurementNoise > 0) { "measurementNoise must be positive" }
    }

    override fun update(rssi: Double, timestampMillis: Long): Double {
        if (!initialised) {
            estimate = rssi
            errorCovariance = measurementNoise
            lastTimestampMillis = timestampMillis
            initialised = true
            return estimate
        }
        // Predict: the true level may have drifted since the last packet.
        val elapsedSeconds = (timestampMillis - lastTimestampMillis).coerceAtLeast(0) / 1000.0
        errorCovariance += processNoisePerSecond * elapsedSeconds
        lastTimestampMillis = timestampMillis
        // Correct: move towards the reading in proportion to the estimate's uncertainty.
        val gain = errorCovariance / (errorCovariance + measurementNoise)
        estimate += gain * (rssi - estimate)
        errorCovariance *= 1 - gain
        return estimate
    }

    override fun reset() {
        initialised = false
    }
}
