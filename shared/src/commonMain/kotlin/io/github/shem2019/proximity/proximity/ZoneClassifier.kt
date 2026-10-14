package io.github.shem2019.proximity.proximity

enum class ProximityZone { Near, Mid, Far }

/**
 * Turns a distance into a zone, with hysteresis so a reading hovering on a boundary does
 * not flicker between two zones.
 *
 * Each boundary is a band rather than a line. To move outwards across a boundary at B the
 * distance must exceed B × [hysteresisRatio]; to move back in it must drop below
 * B ÷ [hysteresisRatio]. Inside the band the previous zone holds.
 *
 * The band is a ratio, not a fixed number of metres, because RSSI noise is additive in dB
 * and therefore multiplicative in distance. A ratio of 1.25 is about ±2 dB when n = 2.
 */
data class ZoneClassifier(
    val nearMaxMeters: Double = 1.0,
    val midMaxMeters: Double = 3.0,
    val hysteresisRatio: Double = 1.25,
) {
    private val boundaries = doubleArrayOf(nearMaxMeters, midMaxMeters)
    private val zones = ProximityZone.entries

    init {
        require(nearMaxMeters > 0) { "nearMaxMeters must be positive" }
        require(midMaxMeters > nearMaxMeters) { "midMaxMeters must exceed nearMaxMeters" }
        require(hysteresisRatio >= 1.0) { "hysteresisRatio must be at least 1" }
    }

    /** The zone for [distanceMeters], given the zone the device was in before (if any). */
    fun classify(distanceMeters: Double, previous: ProximityZone?): ProximityZone {
        if (previous == null) return zones[boundaries.count { distanceMeters > it }]

        var index = previous.ordinal
        // Move out past every boundary the distance is clearly beyond...
        while (index < boundaries.size && distanceMeters > boundaries[index] * hysteresisRatio) index++
        // ...or, if it did not move out, in past every boundary it is clearly inside.
        if (index == previous.ordinal) {
            while (index > 0 && distanceMeters < boundaries[index - 1] / hysteresisRatio) index--
        }
        return zones[index]
    }
}
