package de.df.jauswertung.zeitnahme.android.net

import de.df.jauswertung.zeitnahme.android.capture.StopwatchCapture
import de.df.jauswertung.zeitnahme.android.capture.stopwatchMicros
import de.df.jauswertung.zeitnahme.android.delivery.acknowledged
import de.df.jauswertung.zeitnahme.android.delivery.payload
import de.df.jauswertung.zeitnahme.android.storage.EventKind
import de.df.jauswertung.zeitnahme.android.storage.OutboxEvent
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchContractTest {
    @Test
    fun parsesElapsedTimeExactlyAndRejectsInvalidOrOverflowingInput() {
        assertEquals(65_200_000L, stopwatchMicros(" 1:05,20 "))
        assertEquals(65_200_000L, stopwatchMicros("65.20"))
        assertEquals(1_000_000L, stopwatchMicros("1"))
        assertEquals(1L, stopwatchMicros("0,000001"))
        assertEquals(Long.MAX_VALUE, stopwatchMicros("9223372036854.775807"))

        for (invalid in listOf("", "0", "0:00.0", "-1", "1:60", "1:5", "1,", "1e2", "1.1234567",
            "9223372036854.775808", "9223372036855", "9223372036854775808:00", "9223372036854775807:00")) {
            assertNull(invalid, stopwatchMicros(invalid))
        }
    }

    @Test
    fun rejectsWrongAttemptAndModeAndRecordsTheAssignedStopwatchLaneWithoutClockSync() = runBlocking {
        val origin = DeskOrigin.parse("https://desk.example:8443")
        val generation = UUID.randomUUID()
        val attempt = UUID.randomUUID()
        val heat = DeskHeat(attempt, UUID.randomUUID(), "Freistil", HeatState.LAEUFT, false, false,
            listOf(AssignedLane(3, "A", true), AssignedLane(4, "B", false), AssignedLane(5, "C", false)))
        val events = mutableMapOf<Int, OutboxEvent>()
        val capture = StopwatchCapture { events.putIfAbsent(it.lane, it) == null }
        val duration = checkNotNull(stopwatchMicros("1:05,20"))

        assertNull(capture.record(origin, generation, attempt, heat.copy(state = HeatState.TISCHSTART), 4, duration))
        assertNull(capture.record(origin, generation, UUID.randomUUID(), heat, 4, duration))
        assertNull(capture.record(origin, generation, attempt, heat, 3, duration))
        assertNull(capture.record(origin, generation, attempt, heat, 6, duration))
        assertNull(capture.record(origin, generation, attempt, heat, 4, 0))
        assertTrue(events.isEmpty())

        val event = checkNotNull(capture.record(origin, generation, attempt, heat, 4, duration))
        assertNull(capture.record(origin, generation, attempt, heat, 4, duration + 1))
        assertEquals(EventKind.STOPWATCH, event.kind)
        assertEquals(generation, event.pairingGeneration)
        assertNull(event.clockEpoch)
        assertNull(event.estimateId)
        val body = event.payload()
        assertEquals(setOf("versuch", "ereignis", "bahn", "dauer"), body.keys().asSequence().toSet())
        assertEquals(attempt.toString(), body.getString("versuch"))
        assertEquals(event.id.toString(), body.getString("ereignis"))
        assertEquals(4, body.getInt("bahn"))
        assertEquals(65_200_000L, body.get("dauer"))
        assertTrue(DeskResponse(200, """{"angenommen":true}""").acknowledged())
        assertEquals(mapOf(4 to event), events)

        val mixedHeat = heat.copy(syncRequired = true)
        val second = checkNotNull(capture.record(origin, generation, attempt, mixedHeat, 5, duration))
        assertEquals(setOf(4, 5), events.keys)
        assertNull(second.estimateId)
    }
}
