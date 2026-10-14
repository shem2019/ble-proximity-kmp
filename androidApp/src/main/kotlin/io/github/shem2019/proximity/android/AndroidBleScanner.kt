package io.github.shem2019.proximity.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.shem2019.proximity.ble.Advertisement
import io.github.shem2019.proximity.ble.BleScanException
import io.github.shem2019.proximity.ble.BleScanException.Reason
import io.github.shem2019.proximity.ble.BleScanner
import io.github.shem2019.proximity.ble.ScanStartLimiter
import io.github.shem2019.proximity.ble.ScannerAvailability
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** [BleScanner] on Android's `BluetoothLeScanner`. The only class that touches Bluetooth APIs. */
class AndroidBleScanner(context: Context) : BleScanner {

    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val limiter = ScanStartLimiter()

    private val supported: Boolean
        get() = adapter != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    override fun availability(): Flow<ScannerAvailability> {
        if (!supported) return flowOf(ScannerAvailability.Unsupported)
        return callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    trySend(currentAvailability())
                }
            }
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            trySend(currentAvailability())
            awaitClose { context.unregisterReceiver(receiver) }
        }.distinctUntilChanged()
    }

    private fun currentAvailability(): ScannerAvailability = when {
        adapter?.isEnabled != true -> ScannerAvailability.BluetoothOff
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            !LocationManagerCompat.isLocationEnabled(locationManager) -> ScannerAvailability.LocationServicesOff
        else -> ScannerAvailability.Ready
    }

    // startScan and stopScan need BLUETOOTH_SCAN (API 31+) or location (API 30-); the
    // flow checks BlePermissions.granted before either is called.
    @SuppressLint("MissingPermission")
    override fun advertisements(): Flow<Advertisement> = callbackFlow {
        if (!supported) throw BleScanException(Reason.Unsupported, "This device has no Bluetooth LE")
        if (!BlePermissions.granted(context)) throw BleScanException(Reason.PermissionDenied, "Scan permission missing")
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: throw BleScanException(Reason.BluetoothOff, "Bluetooth is off")

        // A sixth start within 30 s fails silently on Android 7+, so wait instead.
        val wait = synchronized(limiter) { limiter.delayBeforeStart(SystemClock.elapsedRealtime()) }
        if (wait > 0) delay(wait)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toAdvertisement())
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { trySend(it.toAdvertisement()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleScanException(reasonFor(errorCode), "Scan failed with code $errorCode"))
            }
        }
        // Low latency: the screen shows live distance, and the scan only runs while it is
        // visible. No filters, so every advertiser is heard; Android pauses unfiltered
        // scans while the screen is off, which matches the foreground-only design.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        scanner.startScan(null, settings, callback)
        synchronized(limiter) { limiter.recordStart(SystemClock.elapsedRealtime()) }

        awaitClose {
            // stopScan throws if Bluetooth was switched off in the meantime; the scan is gone then anyway.
            runCatching { scanner.stopScan(callback) }
        }
    }.buffer(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun reasonFor(errorCode: Int): Reason = when (errorCode) {
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> Reason.Unsupported
        SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> Reason.TooFrequent
        else -> Reason.Internal
    }

    private fun ScanResult.toAdvertisement() = Advertisement(
        deviceId = device.address,
        // The advertised name needs no extra permission, unlike BluetoothDevice.getName().
        name = scanRecord?.deviceName,
        rssi = rssi,
        txPowerDbm = txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
            ?: scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
        // ScanResult time is elapsedRealtimeNanos, the same clock the ViewModel prunes with.
        timestampMillis = timestampNanos / 1_000_000,
    )

    private companion object {
        /** ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, public only from API 33. */
        const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
    }
}
