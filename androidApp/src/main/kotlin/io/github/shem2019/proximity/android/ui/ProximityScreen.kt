package io.github.shem2019.proximity.android.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shem2019.proximity.android.BlePermissions
import io.github.shem2019.proximity.android.ProximityUiState
import io.github.shem2019.proximity.android.ProximityViewModel
import io.github.shem2019.proximity.android.R
import io.github.shem2019.proximity.android.ScanStatus
import io.github.shem2019.proximity.proximity.CalibrationSource
import io.github.shem2019.proximity.proximity.ProximityReading
import io.github.shem2019.proximity.proximity.ProximityZone
import io.github.shem2019.proximity.signal.Smoothing

/** Wires the screen to the ViewModel and to the system dialogs it needs. */
@Composable
fun ProximityRoute(viewModel: ProximityViewModel) {
    // Collection stops when the app leaves the foreground, which lets the scan stop too.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.isNotEmpty() && results.values.all { it }
        viewModel.onPermissionsChanged(granted)
        permanentlyDenied = !granted && activity != null && BlePermissions.permanentlyDenied(activity)
    }
    // The availability flow sees Bluetooth turn on; the result itself carries nothing extra.
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    ProximityScreen(
        state = state,
        permanentlyDenied = permanentlyDenied,
        onGrant = { requestPermissions.launch(BlePermissions.required) },
        onOpenAppSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
        // BLUETOOTH_CONNECT is granted before this button can appear (permissions come first).
        onTurnOnBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        onOpenLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        onRetry = viewModel::retry,
        onSelect = viewModel::select,
        onSmoothing = viewModel::setSmoothing,
        onCalibrate = viewModel::calibrateAtOneMetre,
        onClearCalibration = viewModel::clearCalibration,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProximityScreen(
    state: ProximityUiState,
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onSmoothing: (Smoothing) -> Unit,
    onCalibrate: (ProximityReading) -> Unit,
    onClearCalibration: (String) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SmoothingSelector(state.smoothing, onSmoothing) }

            if (state.status != ScanStatus.Scanning) {
                item {
                    StatusCard(state.status, permanentlyDenied, onGrant, onOpenAppSettings, onTurnOnBluetooth, onOpenLocationSettings, onRetry)
                }
                return@LazyColumn
            }

            val focused = state.focused
            if (focused == null) {
                item {
                    Text(
                        stringResource(R.string.listening),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                return@LazyColumn
            }

            item(key = "focus") { FocusCard(focused, onCalibrate, onClearCalibration) }
            item(key = "count") {
                Text(
                    pluralStringResource(R.plurals.devices_nearby, state.readings.size, state.readings.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.readings, key = { it.deviceId }) { reading ->
                DeviceRow(reading, selected = reading.deviceId == focused.deviceId, onClick = { onSelect(reading.deviceId) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SmoothingSelector(current: Smoothing, onSmoothing: (Smoothing) -> Unit) {
    Column {
        Text(stringResource(R.string.smoothing_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.size(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Smoothing.entries.forEachIndexed { index, smoothing ->
                SegmentedButton(
                    selected = smoothing == current,
                    onClick = { onSmoothing(smoothing) },
                    shape = SegmentedButtonDefaults.itemShape(index, Smoothing.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (smoothing) {
                                Smoothing.MovingAverage -> R.string.smoothing_moving_average
                                Smoothing.Kalman -> R.string.smoothing_kalman
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: ScanStatus,
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val (title, body, action) = when (status) {
        ScanStatus.NeedsPermission -> Triple(
            stringResource(R.string.status_permission_title),
            stringResource(
                when {
                    permanentlyDenied && legacy -> R.string.status_permission_denied_legacy
                    permanentlyDenied -> R.string.status_permission_denied
                    legacy -> R.string.status_permission_body_legacy
                    else -> R.string.status_permission_body
                },
            ),
            if (permanentlyDenied) {
                stringResource(R.string.action_open_settings) to onOpenAppSettings
            } else {
                stringResource(R.string.action_allow) to onGrant
            },
        )
        ScanStatus.BluetoothOff -> Triple(
            stringResource(R.string.status_bluetooth_off_title),
            stringResource(R.string.status_bluetooth_off_body),
            stringResource(R.string.action_turn_on) to onTurnOnBluetooth,
        )
        ScanStatus.LocationOff -> Triple(
            stringResource(R.string.status_location_off_title),
            stringResource(R.string.status_location_off_body),
            stringResource(R.string.action_location_settings) to onOpenLocationSettings,
        )
        ScanStatus.Unsupported -> Triple(
            stringResource(R.string.status_unsupported_title),
            stringResource(R.string.status_unsupported_body),
            null,
        )
        is ScanStatus.Failed -> Triple(
            stringResource(R.string.status_failed_title),
            stringResource(R.string.status_failed_body, status.reason.name),
            stringResource(R.string.action_retry) to onRetry,
        )
        ScanStatus.Scanning -> return
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            action?.let { (label, onClick) ->
                Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) { Text(label) }
            }
        }
    }
}

@Composable
private fun FocusCard(
    reading: ProximityReading,
    onCalibrate: (ProximityReading) -> Unit,
    onClearCalibration: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reading.name ?: stringResource(R.string.unnamed_device),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                ZoneChip(reading.zone)
            }
            Text(reading.deviceId, style = MaterialTheme.typography.bodySmall)
            Text(
                formatDistance(reading.distanceMeters),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    R.string.distance_range,
                    formatDistance(reading.distanceRange.start),
                    formatDistance(reading.distanceRange.endInclusive),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.rssi_detail,
                    reading.rawRssi,
                    reading.smoothedRssi,
                    pluralStringResource(R.plurals.packets_heard, reading.samples, reading.samples),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.size(4.dp))
            if (reading.calibration == CalibrationSource.Calibrated) {
                Text(stringResource(R.string.calibrated), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onClearCalibration(reading.deviceId) }) {
                    Text(stringResource(R.string.action_clear_calibration))
                }
            } else {
                Text(stringResource(R.string.calibration_default), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onCalibrate(reading) }) { Text(stringResource(R.string.action_calibrate)) }
            }
        }
    }
}

@Composable
private fun DeviceRow(reading: ProximityReading, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(reading.zone.color(), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(reading.name ?: stringResource(R.string.unnamed_device), style = MaterialTheme.typography.bodyLarge)
            Text(reading.deviceId, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDistance(reading.distanceMeters), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.rssi_short, reading.smoothedRssi),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ZoneChip(zone: ProximityZone) {
    Text(
        text = stringResource(
            when (zone) {
                ProximityZone.Near -> R.string.zone_near
                ProximityZone.Mid -> R.string.zone_mid
                ProximityZone.Far -> R.string.zone_far
            },
        ),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(zone.color(), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun formatDistance(meters: Double): String =
    if (meters < 10) {
        stringResource(R.string.distance_metres_decimal, meters)
    } else {
        stringResource(R.string.distance_metres_whole, meters)
    }
