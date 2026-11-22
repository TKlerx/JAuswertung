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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import de.df.jauswertung.zeitnahme.android.net.DeskHeat
import de.df.jauswertung.zeitnahme.android.net.DeskProblem
import de.df.jauswertung.zeitnahme.android.net.HeatState

@Composable
fun TimingScreen(state: TimingScreenState, onPair: (String, String, String) -> Unit, requestNetwork: () -> Unit) {
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
                        Text(stringResource(problem.text()), color = MaterialTheme.colorScheme.error)
                    }
                    when {
                        state.loading -> CircularProgressIndicator()
                        state.pairedName == null -> PairForm(state.networkAllowed && !state.pairing, onPair)
                        else -> HeatContent(state.pairedName, state.heat)
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

@Composable
private fun HeatContent(name: String, heat: DeskHeat?) {
    Text(stringResource(R.string.paired_as, name), style = MaterialTheme.typography.titleMedium)
    if (heat == null) {
        Text(stringResource(R.string.waiting_for_heat))
        return
    }
    Text(heat.discipline, style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.heat_id, heat.id.toString().take(8)))
    Text(stringResource(heat.statusText()), style = MaterialTheme.typography.titleMedium)
    if (heat.lanes.isEmpty()) Text(stringResource(R.string.no_lanes))
    for (lane in heat.lanes) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.lane_number, lane.number), style = MaterialTheme.typography.titleLarge)
                Text(lane.participant, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(if (lane.phoneTiming) R.string.timed_lane else R.string.stopwatch_lane))
            }
        }
    }
}

private fun DeskHeat.statusText(): Int = when (state) {
    HeatState.BEREIT -> if (ready) R.string.heat_ready
        else if (syncRequired) R.string.waiting_for_sync else R.string.heat_armed
    HeatState.TISCHSTART -> R.string.desk_start
    HeatState.FREIGEGEBEN -> R.string.start_pending
    HeatState.START_UNSICHER -> R.string.start_uncertain
    HeatState.LAEUFT -> R.string.heat_running
    HeatState.GESCHLOSSEN -> R.string.heat_closed
    HeatState.UEBERNOMMEN -> R.string.heat_applied
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
