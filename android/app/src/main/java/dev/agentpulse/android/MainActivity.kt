package dev.agentpulse.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dev.agentpulse.android.ui.AppViewModel
import dev.agentpulse.android.ui.PulseApp
import dev.agentpulse.android.ui.theme.PulseTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.factory((application as PulseApplication).container)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        setContent {
            PulseTheme {
                PulseApp(viewModel = viewModel, initialTab = initialTabFromIntent())
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun initialTabFromIntent(): String? = intent.getStringExtra(EXTRA_OPEN_TAB)

    companion object {
        const val EXTRA_OPEN_TAB = "agentpulse.open_tab"
        const val TAB_ALERTS = "alerts"
    }
}
