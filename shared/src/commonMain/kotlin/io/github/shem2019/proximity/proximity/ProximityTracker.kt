package io.github.shem2019.proximity.proximity

import io.github.shem2019.proximity.ble.Advertisement
import io.github.shem2019.proximity.signal.PathLossModel
import io.github.shem2019.proximity.signal.RssiFilter
import io.github.shem2019.proximity.signal.Smoothing

data class ProximityConfig(
    val smoothing: Smoothing = Smoothing.Kalman,
    val pathLossExponent: Double = 2.0,
    val defaultMeasuredPower: Double = PathLossModel.DEFAULT_MEASURED_POWER,
    /** Measured power at 1 m per device id, from the user's own calibration. */
    val calibrations: Map<String, Double> = emptyMap(),
    /**
     * Derive the 1 m power from the advertised TX Power Level when there is no calibration.
     * Off by default: the advertised figure is the radio's output and leaves out antenna
     * and enclosure losses, which differ from device to device.
     */
    val useAdvertisedTxPower: Boolean = false,
    val zones: ZoneClassifier = ZoneClassifier(),
    /** Residual noise assumed after smoothing, used for the distance range. */
    val uncertaintyDb: Double = 3.0,
    /** A device not heard for this long is dropped, and starts fresh if it returns. */
    val staleAfterMillis: Long = 10_000,
)

enum class CalibrationSource { Calibrated, AdvertisedTxPower, Default }

data class ProximityReading(
    val deviceId: String,
    val name: String?,
    val rawRssi: Int,
    val smoothedRssi: Double,
    val distanceMeters: Double,
    val distanceRange: ClosedFloatingPointRange<Double>,
    val zone: ProximityZone,
    val calibration: CalibrationSource,
    val samples: Int,
    val lastSeenMillis: Long,
)

/**
 * Per-device state: one filter, the last zone and the latest reading for every device
 * heard. Not thread-safe; the Flow pipeline drives it from a single coroutine.
 */
class ProximityTracker(config: ProximityConfig = ProximityConfig()) {

    private class Device(var filter: RssiFilter) {
        var reading: ProximityReading? = null
    }

    private val devices = mutableMapOf<String, Device>()

    /**
     * Replacing the config keeps every device and its zone. A new calibration applies from
     * the next packet; a new smoothing method starts each device on a fresh filter.
     */
    var config: ProximityConfig = config
        set(value) {
            if (value.smoothing != field.smoothing) {
                devices.values.forEach { it.filter = value.smoothing.newFilter() }
            }
            field = value
        }

    fun update(advertisement: Advertisement): ProximityReading {
        val device = devices.getOrPut(advertisement.deviceId) { Device(config.smoothing.newFilter()) }
        val previous = device.reading
        val smoothed = device.filter.update(advertisement.rssi.toDouble(), advertisement.timestampMillis)
        val (model, source) = modelFor(advertisement)
        val distance = model.distanceMeters(smoothed)

        return ProximityReading(
            deviceId = advertisement.deviceId,
            name = advertisement.name ?: previous?.name,
            rawRssi = advertisement.rssi,
            smoothedRssi = smoothed,
            distanceMeters = distance,
            distanceRange = model.distanceRange(smoothed, config.uncertaintyDb),
            zone = config.zones.classify(distance, previous?.zone),
            calibration = source,
            samples = (previous?.samples ?: 0) + 1,
            lastSeenMillis = advertisement.timestampMillis,
        ).also { device.reading = it }
    }

    /** Drops devices not heard within [ProximityConfig.staleAfterMillis] of [nowMillis]. */
    fun prune(nowMillis: Long) {
        devices.values.removeAll { device ->
            val lastSeen = device.reading?.lastSeenMillis ?: return@removeAll true
            nowMillis - lastSeen > config.staleAfterMillis
        }
    }

    /** Latest reading per device, nearest first. */
    fun readings(): List<ProximityReading> =
        devices.values.mapNotNull { it.reading }.sortedBy { it.distanceMeters }

    private fun modelFor(advertisement: Advertisement): Pair<PathLossModel, CalibrationSource> {
        val n = config.pathLossExponent
        config.calibrations[advertisement.deviceId]?.let {
            return PathLossModel(it, n) to CalibrationSource.Calibrated
        }
        val txPower = advertisement.txPowerDbm
        if (config.useAdvertisedTxPower && txPower != null) {
            return PathLossModel.fromTxPower(txPower, n) to CalibrationSource.AdvertisedTxPower
        }
        return PathLossModel(config.defaultMeasuredPower, n) to CalibrationSource.Default
    }
}
