package io.github.shem2019.proximity.ble

import kotlinx.coroutines.flow.Flow

/**
 * The boundary between shared logic and the platform's Bluetooth stack.
 *
 * Shared code depends only on this interface; each platform supplies an implementation
 * (`AndroidBleScanner` on Android). Tests use a fake that emits scripted advertisements.
 */
interface BleScanner {

    /** Whether a scan can run right now. Emits again whenever that changes. */
    fun availability(): Flow<ScannerAvailability>

    /**
     * A cold stream of advertisements. Collecting it starts a scan and cancelling the
     * collector stops it, so a scan never outlives the screen that asked for it.
     *
     * The flow fails with [BleScanException] when the platform refuses the scan.
     */
    fun advertisements(): Flow<Advertisement>
}

enum class ScannerAvailability {
    Ready,
    BluetoothOff,

    /** Android 11 and lower return no scan results while location services are off. */
    LocationServicesOff,
    Unsupported,
}

class BleScanException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { BluetoothOff, PermissionDenied, TooFrequent, Unsupported, Internal }
}
