package io.github.shem2019.proximity.signal

/**
 * Mean of the last [windowSize] samples.
 *
 * Simple and predictable. It counts packets, not time: a packet from ten seconds ago weighs
 * the same as one from 100 ms ago, and one deep fade drags the whole window down until it
 * drops out.
 */
class MovingAverageFilter(private val windowSize: Int = 8) : RssiFilter {
    private val window = ArrayDeque<Double>(windowSize)
    private var sum = 0.0

    init {
        require(windowSize >= 1) { "windowSize must be at least 1" }
    }

    override fun update(rssi: Double, timestampMillis: Long): Double {
        window.addLast(rssi)
        sum += rssi
        if (window.size > windowSize) sum -= window.removeFirst()
        return sum / window.size
    }

    override fun reset() {
        window.clear()
        sum = 0.0
    }
}
