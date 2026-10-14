package io.github.shem2019.proximity.signal

/** Smooths a stream of RSSI samples (dBm). Each tracked device gets its own instance. */
interface RssiFilter {
    /** Adds one sample received at [timestampMillis] (monotonic) and returns the smoothed value. */
    fun update(rssi: Double, timestampMillis: Long): Double

    fun reset()
}

/** The smoothing methods the app offers. */
enum class Smoothing {
    MovingAverage,
    Kalman,
    ;

    fun newFilter(): RssiFilter = when (this) {
        MovingAverage -> MovingAverageFilter()
        Kalman -> KalmanFilter()
    }
}
