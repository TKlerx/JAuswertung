package de.df.jauswertung.zeitnahme.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.df.jauswertung.zeitnahme.android.R
import de.df.jauswertung.zeitnahme.android.net.DeskHeat
import de.df.jauswertung.zeitnahme.android.net.HeatState
import de.df.jauswertung.zeitnahme.android.storage.DeliveryStatus
import java.util.UUID

@Composable
internal fun LaneScreen(name: String, state: TimingScreenState, onFinish: (UUID, Int) -> Unit) {
    Text(stringResource(R.string.paired_as, name), style = MaterialTheme.typography.titleMedium)
    val heat = state.heat
    if (heat == null) {
        Text(stringResource(R.string.waiting_for_heat))
        return
    }
    Text(heat.discipline, style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.heat_id, heat.id.toString().take(8)))
    Text(stringResource(heat.statusText()), style = MaterialTheme.typography.titleMedium)
    if (heat.state == HeatState.LAEUFT && heat.syncRequired && !state.syncValid) {
        Text(stringResource(R.string.capture_clock_unavailable), color = MaterialTheme.colorScheme.error)
    }
    if (state.captureStorageFailed) {
        Text(stringResource(R.string.capture_storage_failure), color = MaterialTheme.colorScheme.error)
    }
    if (heat.lanes.isEmpty()) Text(stringResource(R.string.no_lanes))
    for (lane in heat.lanes) {
        val status = state.delivery[lane.number]
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.lane_number, lane.number), style = MaterialTheme.typography.titleLarge)
                Text(lane.participant, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(if (lane.phoneTiming) R.string.timed_lane else R.string.stopwatch_lane))
                if (lane.phoneTiming) {
                    Button(
                        onClick = { onFinish(heat.attempt, lane.number) },
                        enabled = heat.state == HeatState.LAEUFT && state.syncValid &&
                            status == null && lane.number !in state.capturing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) {
                        Text(stringResource(R.string.capture_finish, lane.number))
                    }
                    if (status != null) {
                        Text(stringResource(status.text()), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
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

private fun DeliveryStatus.text(): Int = when (this) {
    DeliveryStatus.PENDING -> R.string.finish_pending
    DeliveryStatus.ACCEPTED -> R.string.finish_accepted
    DeliveryStatus.REJECTED -> R.string.finish_rejected
    DeliveryStatus.BLOCKED -> R.string.finish_blocked
}
