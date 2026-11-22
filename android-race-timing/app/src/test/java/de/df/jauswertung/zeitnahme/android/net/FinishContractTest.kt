package de.df.jauswertung.zeitnahme.android.net

import de.df.jauswertung.zeitnahme.android.capture.FinishCapture
import de.df.jauswertung.zeitnahme.android.clock.ClockEstimate
import de.df.jauswertung.zeitnahme.android.clock.ClockScope
import de.df.jauswertung.zeitnahme.android.delivery.acknowledged
import de.df.jauswertung.zeitnahme.android.delivery.payload
import de.df.jauswertung.zeitnahme.android.storage.OutboxEvent
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishContractTest {
    @Test
    fun preservesTheOriginalCaptureAndAllowsOnlyOneFinishPerTimedLane() = runBlocking {
        val scope = ClockScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val origin = DeskOrigin.parse("https://desk.example:8443")
        val estimate = ClockEstimate(scope, UUID.randomUUID(), UUID.randomUUID(), 100, 2, 1000, 0, 200)
        val heat = DeskHeat(scope.attempt, UUID.randomUUID(), "Freistil", HeatState.LAEUFT, false, true,
            listOf(AssignedLane(3, "A", true), AssignedLane(4, "B", true), AssignedLane(5, "C", false)))
        val events = mutableMapOf<Int, OutboxEvent>()
        val capture = FinishCapture { events.putIfAbsent(it.lane, it) == null }
        assertNull(capture.record(origin, scope, heat.copy(state = HeatState.TISCHSTART), estimate, 3, 1000))
        assertNull(capture.record(origin, scope, heat, estimate, 5, 1000))
        assertNull(capture.record(origin, scope, heat, estimate.copy(uncertainty = 10_001), 3, 1000))
        val first = checkNotNull(capture.record(origin, scope, heat, estimate, 3, 1000))
        assertNull(capture.record(origin, scope, heat, estimate, 3, 2000))
        assertNotNull(capture.record(origin, scope, heat, estimate, 4, 2500))
        val payload = first.payload()
        assertEquals(setOf("versuch", "ereignis", "bahn", "zeit", "epoche", "schaetzung"),
            payload.keys().asSequence().toSet())
        assertEquals(scope.attempt.toString(), payload.getString("versuch"))
        assertEquals(first.id.toString(), payload.getString("ereignis"))
        assertEquals(3, payload.getInt("bahn"))
        assertEquals(1000L, payload.getLong("zeit"))
        assertEquals(scope.epoch.toString(), payload.getString("epoche"))
        assertEquals(estimate.id.toString(), payload.getString("schaetzung"))
        assertEquals(setOf(3, 4), events.keys)
        assertTrue(DeskResponse(200, """{"angenommen":true}""").acknowledged())
        assertFalse(DeskResponse(200, """{"angenommen":"true"}""").acknowledged())
        assertFalse(DeskResponse(409, """{"angenommen":true}""").acknowledged())
    }
}
