package de.df.jauswertung.zeitnahme.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.df.jauswertung.zeitnahme.android.ui.TimingScreen
import de.df.jauswertung.zeitnahme.android.ui.TimingViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val timing: TimingViewModel by viewModels()
    private val localNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateNetworkPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimingScreen(timing.state.collectAsStateWithLifecycle().value, timing::pair, ::requestLocalNetwork)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { timing.observeDesk() }
        }
    }

    override fun onStart() {
        super.onStart()
        updateNetworkPermission()
    }

    private fun requestLocalNetwork() {
        if (Build.VERSION.SDK_INT >= 37) localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun updateNetworkPermission() {
        timing.setNetworkAllowed(Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED)
    }
}
