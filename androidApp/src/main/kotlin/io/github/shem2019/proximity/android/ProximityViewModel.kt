package io.github.shem2019.proximity.android

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.shem2019.proximity.ble.BleScanException
import io.github.shem2019.proximity.ble.BleScanner
import io.github.shem2019.proximity.ble.ScannerAvailability
import io.github.shem2019.proximity.proximity.ProximityConfig
import io.github.shem2019.proximity.proximity.ProximityReading
import io.github.shem2019.proximity.proximity.toProximityReadings
import io.github.shem2019.proximity.signal.Smoothing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

sealed interface ScanStatus {
    data object NeedsPermission : ScanStatus
    data object BluetoothOff : ScanStatus
    data object LocationOff : ScanStatus
    data object Unsupported : ScanStatus
    data object Scanning : ScanStatus
    data class Failed(val reason: BleScanException.Reason) : ScanStatus
}

data class ProximityUiState(
    val status: ScanStatus = ScanStatus.Scanning,
    val readings: List<ProximityReading> = emptyList(),
    /** The device shown large: the one the user tapped, otherwise the nearest. */
    val focused: ProximityReading? = null,
    val smoothing: Smoothing = Smoothing.Kalman,
)

/**
 * Owns the scan. It runs while the screen is collecting [uiState] and for five seconds
 * after, so a rotation keeps it going and leaving the app stops it.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
class ProximityViewModel(
    private val scanner: BleScanner,
    permissionsGranted: Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {

    private val permissions = MutableStateFlow(permissionsGranted)
    private val scanFailure = MutableStateFlow<BleScanException.Reason?>(null)
    private val config = MutableStateFlow(ProximityConfig())
    private val selectedId = MutableStateFlow<String?>(null)

    private val status = combine(permissions, scanner.availability(), scanFailure) { granted, availability, failure ->
        when {
            !granted -> ScanStatus.NeedsPermission
            availability == ScannerAvailability.Unsupported -> ScanStatus.Unsupported
            availability == ScannerAvailability.BluetoothOff -> ScanStatus.BluetoothOff
            availability == ScannerAvailability.LocationServicesOff -> ScanStatus.LocationOff
            failure != null -> ScanStatus.Failed(failure)
            else -> ScanStatus.Scanning
        }
    }.distinctUntilChanged()
        // One availability receiver for both collectors below.
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    private val readings = status.flatMapLatest { status ->
        if (status != ScanStatus.Scanning) return@flatMapLatest flowOf(emptyList())
        scanner.advertisements()
            .catch { e -> if (e is BleScanException) scanFailure.value = e.reason else throw e }
            .toProximityReadings(config, clock)
    }

    val uiState: StateFlow<ProximityUiState> =
        combine(status, readings, selectedId, config) { status, readings, selectedId, config ->
            ProximityUiState(
                status = status,
                readings = readings,
                focused = readings.firstOrNull { it.deviceId == selectedId } ?: readings.firstOrNull(),
                smoothing = config.smoothing,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProximityUiState())

    /** Called on every resume: the user may have changed permissions in Settings. */
    fun onPermissionsChanged(granted: Boolean) {
        permissions.value = granted
    }

    fun retry() {
        scanFailure.value = null
    }

    fun select(deviceId: String) {
        selectedId.value = deviceId
    }

    fun setSmoothing(smoothing: Smoothing) {
        config.update { it.copy(smoothing = smoothing) }
    }

    /** The user holds the device one metre away: its current smoothed RSSI becomes P₁. */
    fun calibrateAtOneMetre(reading: ProximityReading) {
        config.update { it.copy(calibrations = it.calibrations + (reading.deviceId to reading.smoothedRssi)) }
    }

    fun clearCalibration(deviceId: String) {
        config.update { it.copy(calibrations = it.calibrations - deviceId) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProximityViewModel(AndroidBleScanner(context), BlePermissions.granted(context))
            }
        }
    }
}
