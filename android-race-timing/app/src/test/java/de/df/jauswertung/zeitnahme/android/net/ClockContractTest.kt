package de.df.jauswertung.zeitnahme.android.net

import de.df.jauswertung.zeitnahme.android.clock.ClockScope
import de.df.jauswertung.zeitnahme.android.clock.ClockSync
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockContractTest {
    @Test
    fun checksBothWireRequestsAndTheExpiryAndCaptureBoundaries() = runBlocking {
        val scope = ClockScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val deskEpoch = UUID.randomUUID()
        val probe = UUID.randomUUID()
        val id = UUID.randomUUID()
        var reports = 0
        var now = 5_000_100L
        val sync = ClockSync({ now }) { endpoint, json, sent, received ->
            when (endpoint) {
                DeskEndpoint.CLOCK -> {
                    assertEquals(scope.attempt.toString(), json.getString("versuch"))
                    assertEquals(scope.epoch.toString(), json.getString("epoche"))
                    sent?.invoke(100)
                    received?.invoke(120)
                    DeskResponse(200, """{"probe":"$probe","schreibtischEpoche":"$deskEpoch","schreibtischEmpfangen":122,"schreibtischGesendet":125}""")
                }
                DeskEndpoint.PROBE -> {
                    reports++
                    assertEquals(probe.toString(), json.getString("probe"))
                    assertEquals(scope.epoch.toString(), json.getString("epoche"))
                    assertEquals(100L, json.getLong("telefonGesendet"))
                    assertEquals(120L, json.getLong("telefonEmpfangen"))
                    DeskResponse(200, """{"id":"$id","versatz":20,"unsicherheit":9999,"bezugszeit":125,"driftPpm":100,"auffrischungMs":200}""")
                }
                else -> error("Unexpected endpoint $endpoint")
            }
        }
        val estimate = checkNotNull(sync.sample(scope))
        assertEquals(deskEpoch, estimate.deskEpoch)
        assertEquals(id, estimate.id)
        assertEquals(200L, estimate.refreshMs)
        now++
        assertNull(sync.sample(scope))
        assertEquals(1, reports)
        assertEquals(10_125L, estimate.map(scope, 10_105))
        assertNull(estimate.map(scope, 10_106))
        assertNull(estimate.map(scope.copy(attempt = UUID.randomUUID()), 10_105))
        assertNull(estimate.map(scope.copy(epoch = UUID.randomUUID()), 10_105))
        assertNull(estimate.map(scope.copy(pairing = UUID.randomUUID()), 10_105))
        assertNull(estimate.map(scope, Long.MAX_VALUE))
    }
}
