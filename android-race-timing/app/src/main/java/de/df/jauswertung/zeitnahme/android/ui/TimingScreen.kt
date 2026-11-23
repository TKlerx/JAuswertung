package de.df.jauswertung.zeitnahme.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.df.jauswertung.zeitnahme.android.R
import de.df.jauswertung.zeitnahme.android.net.DeskProblem
import de.df.jauswertung.zeitnahme.android.storage.DeliveryStatus
import java.util.UUID

@Composable
fun TimingScreen(
    state: TimingScreenState,
    onPair: (String, String, String) -> Unit,
    requestNetwork: () -> Unit,
    onFinish: (UUID, Int) -> Unit,
    onStopwatch: (UUID, Int, Long) -> Unit,
    onRetry: () -> Unit,
    onExport: () -> Unit = {},
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    if (!state.networkAllowed) {
                        Text(stringResource(R.string.local_network_explanation))
                        Button(onClick = requestNetwork) { Text(stringResource(R.string.allow_local_network)) }
                    }
                    state.problem?.let { problem ->
                        if (problem != DeskProblem.UNREACHABLE || state.heat == null) {
                            Text(stringResource(problem.text()), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.unresolved.any { it.status == DeliveryStatus.PENDING }) {
                        Button(onClick = onRetry, enabled = state.networkAllowed) {
                            Text(stringResource(R.string.retry_pending))
                        }
                    }
                    when {
                        state.loading -> CircularProgressIndicator()
                        state.pairedName == null -> PairForm(state.networkAllowed && !state.pairing, onPair)
                        else -> LaneScreen(state.pairedName, state, onFinish, onStopwatch)
                    }
                    PreviousDeliveries(state)
                    if (state.hasCaptures) {
                        TextButton(onClick = onExport) { Text(stringResource(R.string.export_raw_timing)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairForm(enabled: Boolean, onPair: (String, String, String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    OutlinedTextField(
        address, { address = it }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.desk_address)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedTextField(
        name, { name = it }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.device_name)) }, singleLine = true,
    )
    OutlinedTextField(
        code, { code = it }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.pairing_code)) }, singleLine = true,
    )
    Button(
        onClick = { onPair(address, name, code) },
        enabled = enabled && address.isNotBlank() && name.isNotBlank() && code.isNotBlank(),
    ) { Text(stringResource(R.string.pair)) }
}

private fun DeskProblem.text(): Int = when (this) {
    DeskProblem.INVALID_ADDRESS -> R.string.invalid_address
    DeskProblem.INVALID_PAIR_INPUT -> R.string.invalid_pair_input
    DeskProblem.PAIR_CODE -> R.string.invalid_code
    DeskProblem.PAIR_UNCONFIRMED -> R.string.pair_unconfirmed
    DeskProblem.EXPIRED -> R.string.pair_expired
    DeskProblem.LOCAL_NETWORK -> R.string.local_network_explanation
    DeskProblem.TLS -> R.string.tls_failure
    DeskProblem.UNREACHABLE -> R.string.desk_unreachable
    DeskProblem.INVALID_RESPONSE -> R.string.invalid_response
    DeskProblem.STORAGE -> R.string.pair_storage_failure
}
