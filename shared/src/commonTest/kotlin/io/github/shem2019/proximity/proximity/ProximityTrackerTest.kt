package io.github.shem2019.proximity.proximity

import io.github.shem2019.proximity.ble.Advertisement
import io.github.shem2019.proximity.signal.Smoothing
import kotlin.test.Test
import kotlin.test.assertEquals

fun ad(id: String, rssi: Int, at: Long = 0, name: String? = null, txPower: Int? = null) =
    Advertisement(deviceId = id, name = name, rssi = rssi, txPowerDbm = txPower, timestampMillis = at)

class ProximityTrackerTest {

    @Test
    fun firstReadingUsesTheDefaultModel() {
        val reading = ProximityTracker().update(ad("a", rssi = -79))
        // Default P₁ = -59, n = 2: 20 dB below → 10 m, beyond 3 m → Far.
        assertEquals(10.0, reading.distanceMeters, absoluteTolerance = 1e-9)
        assertEquals(ProximityZone.Far, reading.zone)
        assertEquals(CalibrationSource.Default, reading.calibration)
        assertEquals(1, reading.samples)
    }

    @Test
    fun calibrationOverridesTheDefault() {
        val tracker = ProximityTracker(ProximityConfig(calibrations = mapOf("a" to -69.0)))
        val reading = tracker.update(ad("a", rssi = -79))
        // 10 dB below P₁ = -69 → 10^(10/20) = 3.16 m.
        assertEquals(3.1623, reading.distanceMeters, absoluteTolerance = 1e-4)
        assertEquals(CalibrationSource.Calibrated, reading.calibration)
    }

    @Test
    fun advertisedTxPowerIsUsedOnlyWhenEnabled() {
        val advert = ad("a", rssi = -60, txPower = -20) // P₁ from TX power = -60.196
        assertEquals(CalibrationSource.Default, ProximityTracker().update(advert).calibration)

        val reading = ProximityTracker(ProximityConfig(useAdvertisedTxPower = true)).update(advert)
        assertEquals(CalibrationSource.AdvertisedTxPower, reading.calibration)
        assertEquals(0.9777, reading.distanceMeters, absoluteTolerance = 1e-4)
    }

    @Test
    fun theZoneRemembersWhereTheDeviceWas() {
        // -60 dBm is 1.12 m: inside the 0.8–1.25 m band around the near boundary.
        val tracker = ProximityTracker()
        var now = 0L
        repeat(30) { tracker.update(ad("a", rssi = -50, at = now)); now += 100 } // 0.35 m, Near
        var reading = tracker.update(ad("a", rssi = -60, at = now))
        repeat(200) { now += 100; reading = tracker.update(ad("a", rssi = -60, at = now)) }
        assertEquals(1.122, reading.distanceMeters, absoluteTolerance = 1e-3)
        assertEquals(ProximityZone.Near, reading.zone)

        // A device first heard at the same distance has no history and lands in Mid.
        assertEquals(ProximityZone.Mid, tracker.update(ad("b", rssi = -60)).zone)
    }

    @Test
    fun readingsAreNearestFirst() {
        val tracker = ProximityTracker()
        tracker.update(ad("far", rssi = -85))
        tracker.update(ad("near", rssi = -50))
        tracker.update(ad("mid", rssi = -65))
        assertEquals(listOf("near", "mid", "far"), tracker.readings().map { it.deviceId })
    }

    @Test
    fun devicesHaveIndependentFilters() {
        val tracker = ProximityTracker()
        repeat(20) { tracker.update(ad("a", rssi = -50)) }
        assertEquals(-90.0, tracker.update(ad("b", rssi = -90)).smoothedRssi)
    }

    @Test
    fun keepsTheLastKnownName() {
        val tracker = ProximityTracker()
        tracker.update(ad("a", rssi = -60, name = "Tag 7"))
        assertEquals("Tag 7", tracker.update(ad("a", rssi = -60, name = null)).name)
    }

    @Test
    fun aNewCalibrationAppliesWithoutLosingTheDevice() {
        val tracker = ProximityTracker()
        repeat(5) { tracker.update(ad("a", rssi = -69)) }
        tracker.config = tracker.config.copy(calibrations = mapOf("a" to -69.0))

        val reading = tracker.update(ad("a", rssi = -69))
        assertEquals(6, reading.samples)
        assertEquals(CalibrationSource.Calibrated, reading.calibration)
        assertEquals(1.0, reading.distanceMeters, absoluteTolerance = 1e-9)
    }

    @Test
    fun aNewSmoothingMethodStartsFreshFilters() {
        val tracker = ProximityTracker(ProximityConfig(smoothing = Smoothing.Kalman))
        repeat(20) { tracker.update(ad("a", rssi = -50)) }
        tracker.config = tracker.config.copy(smoothing = Smoothing.MovingAverage)

        // A fresh moving average returns its first sample unchanged.
        assertEquals(-80.0, tracker.update(ad("a", rssi = -80)).smoothedRssi)
    }

    @Test
    fun dropsDevicesThatFallSilent() {
        val tracker = ProximityTracker(ProximityConfig(staleAfterMillis = 10_000))
        tracker.update(ad("a", rssi = -60, at = 0))
        tracker.update(ad("b", rssi = -60, at = 5_000))

        tracker.prune(nowMillis = 10_000)
        assertEquals(setOf("a", "b"), tracker.readings().map { it.deviceId }.toSet())

        tracker.prune(nowMillis = 10_001)
        assertEquals(listOf("b"), tracker.readings().map { it.deviceId })

        // A returning device starts again from its first sample.
        assertEquals(1, tracker.update(ad("a", rssi = -60, at = 12_000)).samples)
    }
}
