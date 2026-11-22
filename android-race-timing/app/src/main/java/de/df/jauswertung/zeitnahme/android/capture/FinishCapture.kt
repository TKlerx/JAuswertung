package de.df.jauswertung.zeitnahme.android.capture

import de.df.jauswertung.zeitnahme.android.clock.ClockEstimate
import de.df.jauswertung.zeitnahme.android.clock.ClockScope
import de.df.jauswertung.zeitnahme.android.net.DeskHeat
import de.df.jauswertung.zeitnahme.android.net.DeskOrigin
import de.df.jauswertung.zeitnahme.android.net.HeatState
import de.df.jauswertung.zeitnahme.android.storage.EventKind
import de.df.jauswertung.zeitnahme.android.storage.OutboxEvent
import java.util.UUID

internal class FinishCapture(private val enqueue: suspend (OutboxEvent) -> Boolean) {
    suspend fun record(
        origin: DeskOrigin,
        scope: ClockScope,
        heat: DeskHeat,
        estimate: ClockEstimate,
        lane: Int,
        capturedAt: Long,
    ): OutboxEvent? {
        if (heat.attempt != scope.attempt || heat.state != HeatState.LAEUFT ||
            heat.lanes.none { it.number == lane && it.phoneTiming } || estimate.map(scope, capturedAt) == null
        ) return null
        val event = OutboxEvent(
            UUID.randomUUID(), scope.pairing, origin.value, scope.attempt, lane, EventKind.FINISH,
            capturedAt, scope.epoch, estimate.id,
        )
        return event.takeIf { enqueue(event) }
    }
}
