package io.github.shem2019.proximity.proximity

import io.github.shem2019.proximity.proximity.ProximityZone.Far
import io.github.shem2019.proximity.proximity.ProximityZone.Mid
import io.github.shem2019.proximity.proximity.ProximityZone.Near
import io.github.shem2019.proximity.signal.GaussianNoise
import io.github.shem2019.proximity.signal.KalmanFilter
import io.github.shem2019.proximity.signal.PathLossModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZoneClassifierTest {

    // Boundaries at 1 m and 3 m; bands 0.8–1.25 m and 2.4–3.75 m.
    private val classifier = ZoneClassifier(nearMaxMeters = 1.0, midMaxMeters = 3.0, hysteresisRatio = 1.25)

    @Test
    fun firstReadingUsesThePlainBoundaries() {
        assertEquals(Near, classifier.classify(0.5, previous = null))
        assertEquals(Near, classifier.classify(1.0, previous = null))
        assertEquals(Mid, classifier.classify(1.01, previous = null))
        assertEquals(Mid, classifier.classify(3.0, previous = null))
        assertEquals(Far, classifier.classify(3.5, previous = null))
    }

    @Test
    fun leavingNearNeedsToClearTheBand() {
        assertEquals(Near, classifier.classify(1.2, previous = Near))
        assertEquals(Mid, classifier.classify(1.3, previous = Near))
    }

    @Test
    fun enteringNearNeedsToClearTheBand() {
        assertEquals(Mid, classifier.classify(0.85, previous = Mid))
        assertEquals(Near, classifier.classify(0.75, previous = Mid))
    }

    @Test
    fun theOuterBoundaryHasItsOwnBand() {
        assertEquals(Mid, classifier.classify(3.7, previous = Mid))
        assertEquals(Far, classifier.classify(3.8, previous = Mid))
        assertEquals(Far, classifier.classify(2.5, previous = Far))
        assertEquals(Mid, classifier.classify(2.3, previous = Far))
    }

    @Test
    fun aBigJumpCrossesBothBoundariesAtOnce() {
        assertEquals(Far, classifier.classify(5.0, previous = Near))
        assertEquals(Near, classifier.classify(0.5, previous = Far))
    }

    @Test
    fun ratioOfOneMeansNoMemory() {
        val plain = classifier.copy(hysteresisRatio = 1.0)
        assertEquals(Mid, plain.classify(1.01, previous = Near))
        assertEquals(Near, plain.classify(0.99, previous = Mid))
    }

    @Test
    fun hysteresisStopsFlickerAtABoundary() {
        // A device standing exactly on the 1 m boundary, ten packets a second scattered by
        // 4 dB and smoothed by the Kalman filter. Count how often the shown zone changes.
        fun zoneChanges(zones: ZoneClassifier): Int {
            val noise = GaussianNoise(seed = 3, sigma = 4.0)
            val model = PathLossModel(measuredPowerAt1m = -59.0)
            val filter = KalmanFilter()
            var zone: ProximityZone? = null
            var changes = 0
            repeat(1_000) { i ->
                val distance = model.distanceMeters(filter.update(-59.0 + noise.next(), i * 100L))
                val next = zones.classify(distance, zone)
                if (zone != null && next != zone) changes++
                zone = next
            }
            return changes
        }

        val without = zoneChanges(classifier.copy(hysteresisRatio = 1.0))
        val with = zoneChanges(classifier)
        assertTrue(without > 30, "without hysteresis: $without changes")
        assertTrue(with * 5 < without, "with hysteresis: $with changes, without: $without")
    }

    @Test
    fun rejectsInconsistentSettings() {
        assertFailsWith<IllegalArgumentException> { ZoneClassifier(nearMaxMeters = 2.0, midMaxMeters = 1.0) }
        assertFailsWith<IllegalArgumentException> { ZoneClassifier(hysteresisRatio = 0.9) }
    }
}
