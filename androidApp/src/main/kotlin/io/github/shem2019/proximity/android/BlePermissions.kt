package io.github.shem2019.proximity.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** The runtime permissions a BLE scan needs, which depend on the Android version. */
object BlePermissions {

    val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * After a refusal, Android stops showing the dialog once the user has said no twice
     * (or ticked "don't ask again"). Only the settings screen can grant it then.
     */
    fun permanentlyDenied(activity: Activity): Boolean =
        !granted(activity) && required.none { activity.shouldShowRequestPermissionRationale(it) }
}
