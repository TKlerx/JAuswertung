package de.df.jauswertung.zeitnahme.android.capture

import de.df.jauswertung.zeitnahme.android.net.DeskHeat
import de.df.jauswertung.zeitnahme.android.net.DeskOrigin
import de.df.jauswertung.zeitnahme.android.net.HeatState
import de.df.jauswertung.zeitnahme.android.storage.EventKind
import de.df.jauswertung.zeitnahme.android.storage.OutboxEvent
import java.util.UUID

private val stopwatchFormat = Regex("""(?:(\d+):([0-5]\d)|(\d+))(?:[,.](\d{1,6}))?""")

internal fun stopwatchMicros(input: String): Long? {
    val (minutes, seconds, secondsOnly, fraction) =
        stopwatchFormat.matchEntire(input.trim())?.destructured ?: return null
    return try {
        val whole = if (secondsOnly.isNotEmpty()) secondsOnly.toLong()
        else Math.addExact(Math.multiplyExact(minutes.toLong(), 60), seconds.toLong())
        Math.addExact(Math.multiplyExact(whole, 1_000_000), fraction.padEnd(6, '0').toLong())
            .takeIf { it > 0 }
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

internal class StopwatchCapture(private val enqueue: suspend (OutboxEvent) -> Boolean) {
    suspend fun record(
        origin: DeskOrigin,
        pairingGeneration: UUID,
        attempt: UUID,
        heat: DeskHeat,
        lane: Int,
        duration: Long,
    ): OutboxEvent? {
        if (heat.attempt != attempt || heat.state != HeatState.LAEUFT || duration <= 0 ||
            heat.lanes.none { it.number == lane && !it.phoneTiming }
        ) return null
        val event = OutboxEvent(UUID.randomUUID(), pairingGeneration, origin.value, attempt, lane,
            EventKind.STOPWATCH, duration)
        return event.takeIf { enqueue(event) }
    }
}
