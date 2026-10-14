package io.github.shem2019.proximity.signal

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow

/**
 * Log-distance path-loss model: RSSI(d) = P₁ − 10·n·log₁₀(d), so d = 10^((P₁ − RSSI) / 10n).
 *
 * @property measuredPowerAt1m P₁, the RSSI expected at one metre, in dBm. −59 dBm is the
 *   common default for phones and beacons; calibrating per device does better.
 * @property pathLossExponent n. 2.0 in free space; 2.5–3.5 indoors among walls and people;
 *   below 2 in corridors that guide the signal.
 */
data class PathLossModel(
    val measuredPowerAt1m: Double = DEFAULT_MEASURED_POWER,
    val pathLossExponent: Double = 2.0,
) {
    init {
        require(pathLossExponent > 0) { "pathLossExponent must be positive" }
    }

    /** Estimated distance in metres for a (preferably smoothed) RSSI. */
    fun distanceMeters(rssi: Double): Double =
        10.0.pow((measuredPowerAt1m - rssi) / (10 * pathLossExponent))

    /** The RSSI this model expects at [meters]. Inverse of [distanceMeters]. */
    fun rssiAt(meters: Double): Double {
        require(meters > 0) { "distance must be positive" }
        return measuredPowerAt1m - 10 * pathLossExponent * log10(meters)
    }

    /**
     * Distances consistent with [rssi] give or take [uncertaintyDb]. An error of e dB
     * multiplies distance by 10^(e / 10n): with n = 2, ±4 dB spans ×0.63 to ×1.58.
     */
    fun distanceRange(rssi: Double, uncertaintyDb: Double): ClosedFloatingPointRange<Double> =
        distanceMeters(rssi + uncertaintyDb)..distanceMeters(rssi - uncertaintyDb)

    companion object {
        const val DEFAULT_MEASURED_POWER = -59.0

        /** Middle of the 2.4 GHz band BLE advertises in (channels 37–39, 2402–2480 MHz). */
        const val BLE_FREQUENCY_HZ = 2.44e9
        private const val SPEED_OF_LIGHT = 299_792_458.0

        /** Free-space path loss over [meters] at [frequencyHz]: 20·log₁₀(4πdf / c). */
        fun freeSpaceLossDb(meters: Double = 1.0, frequencyHz: Double = BLE_FREQUENCY_HZ): Double =
            20 * log10(4 * PI * meters * frequencyHz / SPEED_OF_LIGHT)

        /**
         * A model calibrated from the advertised TX Power Level (dBm at 0 m): the power at
         * one metre is that level minus the free-space loss over the first metre (≈ 40 dB).
         */
        fun fromTxPower(txPowerDbm: Int, pathLossExponent: Double = 2.0) =
            PathLossModel(txPowerDbm - freeSpaceLossDb(), pathLossExponent)
    }
}
