package io.github.shem2019.proximity.ble

/**
 * One advertising packet as the platform reported it.
 *
 * @property deviceId identifier for the advertiser during this scan session (a MAC address
 *   on Android, a UUID on iOS). Devices with private addresses rotate it every few minutes.
 * @property rssi received signal strength in dBm.
 * @property txPowerDbm the "TX Power Level" the device advertises, in dBm at 0 m, or null
 *   when the packet does not carry it.
 * @property timestampMillis monotonic time the packet was received, in milliseconds.
 */
data class Advertisement(
    val deviceId: String,
    val name: String?,
    val rssi: Int,
    val txPowerDbm: Int?,
    val timestampMillis: Long,
)
