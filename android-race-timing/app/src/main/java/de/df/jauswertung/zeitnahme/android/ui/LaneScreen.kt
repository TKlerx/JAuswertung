package de.df.jauswertung.zeitnahme.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.df.jauswertung.zeitnahme.android.R
import de.df.jauswertung.zeitnahme.android.capture.stopwatchMicros
import de.df.jauswertung.zeitnahme.android.net.DeskHeat
import de.df.jauswertung.zeitnahme.android.net.HeatState
import de.df.jauswertung.zeitnahme.android.storage.DeliveryStatus
import de.df.jauswertung.zeitnahme.android.storage.OutboxEvent
import java.util.UUID

@Composable
internal fun LaneScreen(
    name: String,
    state: TimingScreenState,
    onFinish: (UUID, Int) -> Unit,
    onStopwatch: (UUID, Int, Long) -> Unit,
) {
    Text(stringResource(R.string.paired_as, name), style = MaterialTheme.typography.titleMedium)
    val rtt = state.roundTripMillis
    Text(if (rtt == null) stringResource(R.string.desk_rtt_unavailable) else stringResource(R.string.desk_rtt, rtt),
        style = MaterialTheme.typography.bodySmall)
    val heat = state.heat
    if (heat == null) {
        Text(stringResource(R.string.waiting_for_heat))
        return
    }
    Text(heat.discipline, style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.heat_id, heat.id.toString().take(8)))
    if (!state.networkAllowed || state.problem != null) {
        Text(stringResource(R.string.last_known_heat))
    }
    Text(stringResource(heat.statusText()), style = MaterialTheme.typography.titleMedium)
    state.runningSeconds?.let { seconds ->
        Text(stringResource(R.string.desk_elapsed, seconds / 60, seconds % 60),
            style = MaterialTheme.typography.titleMedium)
    }
    if (heat.state == HeatState.LAEUFT && heat.syncRequired && !state.syncValid) {
        Text(stringResource(R.string.capture_clock_unavailable), color = MaterialTheme.colorScheme.error)
    }
    if (state.captureStorageFailed) {
        Text(stringResource(R.string.capture_storage_failure), color = MaterialTheme.colorScheme.error)
    }
    if (heat.lanes.isEmpty()) Text(stringResource(R.string.no_lanes))
    for (lane in heat.lanes) {
        val event = state.delivery[lane.number]
        val canCapture = state.canCapture(lane.number, lane.phoneTiming)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.lane_number, lane.number), style = MaterialTheme.typography.titleLarge)
                Text(lane.participant, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(if (lane.phoneTiming) R.string.timed_lane else R.string.stopwatch_lane))
                if (lane.phoneTiming) {
                    Button(
                        onClick = { onFinish(heat.attempt, lane.number) },
                        enabled = canCapture,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) {
                        Text(stringResource(R.string.capture_finish, lane.number))
                    }
                } else if (event == null) {
                    key(heat.attempt, lane.number) {
                        StopwatchEntry(lane.number, canCapture) { onStopwatch(heat.attempt, lane.number, it) }
                    }
                }
                if (event != null) {
                    DeliveryOutcome(event)
                }
            }
        }
    }
}

@Composable
internal fun PreviousDeliveries(state: TimingScreenState) {
    val currentIds = state.delivery.values.mapTo(mutableSetOf(), OutboxEvent::id)
    val previous = state.unresolved.filter { it.id !in currentIds }
    if (previous.isEmpty()) return
    Text(stringResource(R.string.previous_deliveries), style = MaterialTheme.typography.titleMedium)
    for (event in previous) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.previous_delivery_lane, event.lane, event.attemptId.toString().take(8)))
            DeliveryOutcome(event)
        }
    }
}

@Composable
private fun DeliveryOutcome(event: OutboxEvent) {
    val text = when (event.status) {
        DeliveryStatus.PENDING -> R.string.finish_pending
        DeliveryStatus.ACCEPTED -> R.string.finish_accepted
        DeliveryStatus.BLOCKED -> if (event.reason == "pairing_unavailable") R.string.finish_blocked_old_pairing
        else R.string.finish_blocked
        DeliveryStatus.REJECTED -> when (event.reason) {
            "geschlossen", "veraltet" -> R.string.finish_rejected_closed
            "schaetzung" -> R.string.finish_rejected_estimate
            "rolle" -> R.string.finish_rejected_lane
            "ungueltig" -> R.string.finish_rejected_invalid
            else -> R.string.finish_rejected
        }
    }
    Text(stringResource(text), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun StopwatchEntry(lane: Int, enabled: Boolean, onSave: (Long) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it
            invalid = false
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(R.string.stopwatch_input, lane)) },
        supportingText = {
            if (invalid) {
                Text(stringResource(R.string.stopwatch_input_error),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else {
                Text(stringResource(R.string.stopwatch_input_format))
            }
        },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
    Button(
        onClick = {
            val duration = stopwatchMicros(input)
            invalid = duration == null
            if (duration != null) {
                focus.clearFocus()
                onSave(duration)
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Text(stringResource(R.string.stopwatch_save, lane))
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
