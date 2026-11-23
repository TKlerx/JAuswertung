package de.df.jauswertung.zeitnahme.android.net

import de.df.jauswertung.zeitnahme.android.storage.DeskPairing
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class PairingHeatContractTest {
    private val origin = DeskOrigin.parse("https://desk.example:8443")
    private val attempt = UUID.randomUUID()
    private val heatId = UUID.randomUUID()
    private var saved: DeskPairing? = null

    @Test
    fun pairsWithoutBearerAndReadsOnlyAssignedLanesWithBearer() = runBlocking {
        var reads = 0
        val session = session { url, endpoint, data, pairing ->
            assertEquals(origin, url)
            when (endpoint) {
                DeskEndpoint.PAIR -> {
                    assertNull(pairing)
                    assertEquals(setOf("code", "name"), data.keys().asSequence().toSet())
                    assertEquals("code-1", data.getString("code"))
                    assertEquals("Bahnaufsicht", data.getString("name"))
                    DeskResponse(200, """{"zugang":"test-bearer"}""")
                }
                DeskEndpoint.HEAT -> {
                    assertEquals("test-bearer", pairing?.bearer)
                    assertEquals(emptySet<String>(), data.keys().asSequence().toSet())
                    if (reads++ == 0) DeskResponse(200, """{"name":"Bahnaufsicht","starter":false}""")
                    else DeskResponse(200, activeHeat)
                }
                else -> error("Unexpected endpoint: $endpoint")
            }
        }
        session.pair(origin, "Bahnaufsicht", "code-1")
        assertEquals(origin, saved?.origin)
        assertNull(session.refresh())
        val heat = requireNotNull(session.refresh())
        assertEquals(attempt, heat.attempt)
        assertEquals(heatId, heat.id)
        assertEquals(HeatState.BEREIT, heat.state)
        assertEquals(listOf(AssignedLane(3, "Team Drei", true), AssignedLane(4, "Team Vier", false)), heat.lanes)
        assertEquals(heat, session.lastHeat)
    }

    @Test
    fun readsRecordedStartAndDeskEpochWithoutRequiringThemOnOlderDesks() = runBlocking {
        saved = DeskPairing(origin, "Bahnaufsicht", UUID.randomUUID(), "test-bearer", false)
        val epoch = UUID.randomUUID()
        val running = activeHeat.replace("BEREIT", "LAEUFT")
        var reply = running
        val session = session { _, _, _, _ -> DeskResponse(200, reply, 7_200) }
        session.restore()
        assertNull(session.refresh()?.startedAt)
        reply = JSONObject(running).put("startZeitUs", -123_456L).put("schreibtischEpoche", epoch.toString()).toString()
        assertEquals(DeskStart(-123_456L, epoch), session.refresh()?.startedAt)
        assertEquals(7_200L, session.lastRoundTripMicros)
    }

    @Test
    fun distinguishesConsumedCodeFromAmbiguousResponseWithoutResending() = runBlocking {
        var requests = 0
        val session = session { _, _, _, _ ->
            if (requests++ == 0) DeskResponse(403, """{"fehler":"code"}""") else throw IOException()
        }
        expect(DeskProblem.PAIR_CODE) { session.pair(origin, "Bahnaufsicht", "code-1") }
        expect(DeskProblem.PAIR_UNCONFIRMED) { session.pair(origin, "Bahnaufsicht", "code-2") }
        assertEquals(2, requests)
        assertNull(saved)
    }

    @Test
    fun rejectsDuplicateLanesAndClearsHeatWhenTheDeskExpiresThePairing() = runBlocking {
        saved = DeskPairing(origin, "Bahnaufsicht", UUID.randomUUID(), "test-bearer", false)
        var next = DeskResponse(200, activeHeat)
        val session = session { _, _, _, _ -> next }
        session.restore()
        val heat = session.refresh()
        next = DeskResponse(200, activeHeat.replace("\"nummer\":4", "\"nummer\":3"))
        expect(DeskProblem.INVALID_RESPONSE) { session.refresh() }
        assertEquals(heat, session.lastHeat)
        next = DeskResponse(401, """{"fehler":"nichtGekoppelt"}""")
        expect(DeskProblem.EXPIRED) { session.refresh() }
        assertNull(session.lastHeat)
        assertEquals(true, saved?.expired)
    }

    private fun session(post: suspend (DeskOrigin, DeskEndpoint, JSONObject, DeskPairing?) -> DeskResponse) =
        DeskSession(post, { saved }, { url, name, token ->
            DeskPairing(url, name, UUID.randomUUID(), token, false).also { saved = it }
        }, { generation ->
            val before = requireNotNull(saved)
            assertEquals(before.generation, generation)
            saved = DeskPairing(before.origin, before.displayName, generation, before.bearer, true)
        })

    private suspend fun expect(problem: DeskProblem, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected $problem")
        } catch (error: DeskFailure) {
            assertEquals(problem, error.problem)
        }
    }

    private val activeHeat get() = """
        {"versuch":"$attempt","lauf":"$heatId","disziplin":"Probe","zustand":"BEREIT",
         "bereit":false,"abgleich":true,"bahnen":[
           {"nummer":3,"teilnehmer":"Team Drei","telefonzeit":true},
           {"nummer":4,"teilnehmer":"Team Vier","telefonzeit":false}
         ]}
    """.trimIndent()
}
