package io.github.shem2019.proximity.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.shem2019.proximity.android.ui.ProximityRoute
import io.github.shem2019.proximity.android.ui.ProximityTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ProximityViewModel by viewModels { ProximityViewModel.factory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProximityTheme {
                ProximityRoute(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can change in Settings while the app is in the background.
        viewModel.onPermissionsChanged(BlePermissions.granted(this))
    }
}
