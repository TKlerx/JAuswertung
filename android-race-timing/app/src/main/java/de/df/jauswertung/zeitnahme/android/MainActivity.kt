package de.df.jauswertung.zeitnahme.android

import android.Manifest
import android.content.pm.PackageManager
import android.database.SQLException
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val timing: TimingViewModel by viewModels()
    private val localNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateNetworkPermission()
    }
    private val rawTimingFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = try {
                val csv = timing.exportRawTiming()
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri) ?: throw IOException()
                    output.bufferedWriter().use { it.write(csv) }
                }
                R.string.export_raw_timing_success
            } catch (_: IOException) {
                R.string.export_raw_timing_failure
            } catch (_: SecurityException) {
                R.string.export_raw_timing_failure
            } catch (_: SQLException) {
                R.string.export_raw_timing_failure
            }
            Toast.makeText(this@MainActivity, result, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimingScreen(timing.state.collectAsStateWithLifecycle().value, timing::pair, ::requestLocalNetwork,
                timing::captureFinish, timing::captureStopwatch, timing::retryPending,
                { rawTimingFile.launch("zeitnahme-android-rohdaten.csv") })
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
