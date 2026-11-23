package de.df.jauswertung.zeitnahme.android

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import de.df.jauswertung.zeitnahme.android.delivery.OutboxSender
import de.df.jauswertung.zeitnahme.android.delivery.acknowledged
import de.df.jauswertung.zeitnahme.android.delivery.payload
import de.df.jauswertung.zeitnahme.android.net.DeskEndpoint
import de.df.jauswertung.zeitnahme.android.net.DeskHttp
import de.df.jauswertung.zeitnahme.android.net.DeskOrigin
import de.df.jauswertung.zeitnahme.android.net.DeskResponse
import de.df.jauswertung.zeitnahme.android.net.HeatState
import de.df.jauswertung.zeitnahme.android.storage.DeliveryStatus
import de.df.jauswertung.zeitnahme.android.storage.DeskPairing
import de.df.jauswertung.zeitnahme.android.storage.EventKind
import de.df.jauswertung.zeitnahme.android.storage.OutboxEvent
import de.df.jauswertung.zeitnahme.android.storage.PairingStore
import de.df.jauswertung.zeitnahme.android.storage.TimingDatabase
import de.df.jauswertung.zeitnahme.android.ui.TimingViewModel
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class OutboxRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val origin = DeskOrigin.parse("https://desk.example:8443")
    private val pairing = DeskPairing(origin, "Testgerät", UUID.randomUUID(), "test-token", false)
    private val attempt = UUID.randomUUID()

    @Test
    fun rollbackBeforeCommitLeavesNoPendingEvent() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, TimingDatabase::class.java).build().useDatabase { db ->
            try {
                db.withTransaction {
                    assertTrue(db.outbox().enqueue(event(3)))
                    throw IOException("simulated transaction failure")
                }
                @Suppress("UNREACHABLE_CODE")
                fail("The transaction should have failed")
            } catch (_: IOException) {
                assertTrue(db.outbox().pending().isEmpty())
                assertTrue(db.outbox().observe().first().isEmpty())
            }
        }
    }

    @Test
    fun reopensTheQueueAndRetriesTheOriginalPayload() = runBlocking {
        val name = "outbox-recovery-test"
        context.deleteDatabase(name)
        val event = event(3)
        val received = mutableListOf<String>()
        try {
            Room.databaseBuilder(context, TimingDatabase::class.java, name).build().useDatabase { db ->
                assertTrue(db.outbox().enqueue(event))
                val sender = OutboxSender(db.outbox(), { pairing }, {}) { _, _, _, payload ->
                    received += payload.toString()
                    throw IOException("simulated lost acknowledgement")
                }
                assertTrue(sender.sendPending())
            }
            Room.databaseBuilder(context, TimingDatabase::class.java, name).build().useDatabase { db ->
                val pending = db.outbox().pending().single()
                assertEquals(event, pending.copy(sendAttempts = 0, reason = null))
                val sender = OutboxSender(db.outbox(), { pairing }, {}) { _, _, _, payload ->
                    received += payload.toString()
                    DeskResponse(200, """{"angenommen":true}""")
                }
                assertFalse(sender.sendPending())
                assertEquals(received.first(), received.last())
                assertEquals(2, received.size)
                assertEquals(DeliveryStatus.ACCEPTED, db.outbox().observe().first().single().status)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun blocksExpiredPairingAndKeepsRejectedAndBlockedEventsVisible() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, TimingDatabase::class.java).build().useDatabase { db ->
            val queue = db.outbox()
            for (lane in 3..5) assertTrue(queue.enqueue(event(lane)))
            val generation = pairing.generation
            var expired: UUID? = null
            var requests = 0
            val sender = OutboxSender(queue, { pairing }, { expired = it }) { _, _, _, _ ->
                if (++requests == 1) DeskResponse(409, """{"fehler":"veraltet"}""") else DeskResponse(401, "")
            }
            assertFalse(sender.sendPending())
            val events = queue.observe().first()
            assertEquals(DeliveryStatus.REJECTED, events[0].status)
            assertEquals("veraltet", events[0].reason)
            assertEquals(listOf(DeliveryStatus.BLOCKED, DeliveryStatus.BLOCKED), events.drop(1).map { it.status })
            assertEquals(generation, expired)
            assertTrue(queue.pending().isEmpty())
            assertEquals(2, requests)
        }
    }

    @Test
    fun dropAcknowledgement() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("restart") == "first")
        val address = requireNotNull(args.getString("desk"))
        val code = requireNotNull(args.getString("code"))
        val http = DeskHttp(context)
        val sent = AtomicBoolean()
        val accepted = CompletableDeferred<JSONObject>()
        val model = TimingViewModel(context.applicationContext as Application, http) {
                host, endpoint, payload, credentials, onSent, onReceived ->
            if (endpoint != DeskEndpoint.FINISH) {
                http.post(host, endpoint, payload, credentials, onSent, onReceived)
            } else {
                if (sent.compareAndSet(false, true)) {
                    val response = http.post(host, endpoint, payload, credentials)
                    check(response.acknowledged()) { "Desk did not accept the first finish (HTTP ${response.status})" }
                    accepted.complete(JSONObject()
                        .put("status", response.status)
                        .put("response", JSONObject(response.body))
                        .put("payload", payload)
                        .put("pairing", requireNotNull(credentials).generation.toString())
                        .put("pid", android.os.Process.myPid()))
                }
                throw IOException("withheld acknowledgement")
            }
        }
        foreground(model) {
            val idle = waitFor { model.state.value.takeUnless { it.loading } }
            assertTrue("Use fresh pilot app data", idle.pairedName == null)
            withContext(Dispatchers.Main) { model.pair(address, "Android-Outbox", code) }
            val ready = waitFor {
                model.state.value.takeIf { it.syncValid && it.heat?.ready == true }
            }
            val heat = requireNotNull(ready.heat)
            val lanes = heat.lanes.filter { it.phoneTiming }
            assertEquals(2, lanes.size)
            val lane = lanes.first().number
            writeEvidence("outbox-ready.json", JSONObject().put("attempt", heat.attempt).put("lane", lane))
            waitFor { model.state.value.takeIf { it.canCapture(lane, true) } }
            withContext(Dispatchers.Main) { model.captureFinish(heat.attempt, lane) }
            val proof = withTimeout(30_000) { accepted.await() }
            waitFor {
                model.state.value.delivery[lane]?.takeIf {
                    it.status == DeliveryStatus.PENDING && it.sendAttempts > 0
                }
            }
            writeEvidence("outbox-first.json", proof)
            waitFor { Unit.takeIf { File(context.filesDir, "outbox-exported").exists() } }
        }
    }

    @Test
    fun replayAfterRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restart") == "second")
        val first = JSONObject(File(context.filesDir, "outbox-first.json").readText())
        val original = first.getJSONObject("payload")
        val lane = original.getInt("bahn")
        val resumeDelivery = CompletableDeferred<Unit>()
        val resumeClock = CompletableDeferred<Unit>()
        val replayed = CompletableDeferred<JSONObject>()
        val http = DeskHttp(context)
        val model = TimingViewModel(context.applicationContext as Application, http) {
                host, endpoint, payload, credentials, onSent, onReceived ->
            if (endpoint == DeskEndpoint.CLOCK) resumeClock.await()
            if (endpoint == DeskEndpoint.FINISH) resumeDelivery.await()
            val response = http.post(host, endpoint, payload, credentials, onSent, onReceived)
            if (endpoint == DeskEndpoint.FINISH && lane == payload.getInt("bahn")) {
                assertTrue(response.acknowledged())
                replayed.complete(payload)
            }
            response
        }
        foreground(model) {
            val restored = waitFor {
                model.state.value.takeIf { it.heat?.state == HeatState.LAEUFT && lane in it.delivery }
            }
            val heat = requireNotNull(restored.heat)
            val second = heat.lanes.single { it.phoneTiming && it.number != lane }.number
            val pending = restored.delivery.getValue(lane)
            assertEquals(DeliveryStatus.PENDING, pending.status)
            assertTrue(restored.unresolved.any { it.id == pending.id })
            assertEquals(first.getString("pairing"), pending.pairingGeneration.toString())
            assertEquals(original.toString(), pending.payload().toString())
            assertNotEquals(first.getInt("pid"), android.os.Process.myPid())
            assertFalse(restored.canCapture(second, true))
            withContext(Dispatchers.Main) { model.captureFinish(heat.attempt, second) }
            assertFalse(second in model.state.value.delivery)

            resumeDelivery.complete(Unit)
            assertEquals(original.toString(), withTimeout(30_000) { replayed.await() }.toString())
            waitFor { model.state.value.delivery[lane]?.takeIf { it.status == DeliveryStatus.ACCEPTED } }
            resumeClock.complete(Unit)
            waitFor { model.state.value.takeIf { it.canCapture(second, true) } }
            withContext(Dispatchers.Main) { model.captureFinish(heat.attempt, second) }
            val secondEvent = waitFor {
                model.state.value.delivery[second]?.takeIf { it.status == DeliveryStatus.ACCEPTED }
            }
            assertNotEquals(pending.clockEpoch, secondEvent.clockEpoch)
            assertNotEquals(pending.estimateId, secondEvent.estimateId)
            writeEvidence("outbox-replayed.json", JSONObject()
                .put("restoredPending", true)
                .put("pendingVisible", true)
                .put("blockedBeforeFreshClock", true)
                .put("pid", android.os.Process.myPid())
                .put("replay", replayed.await())
                .put("second", secondEvent.payload()))

            waitFor { File(context.filesDir, "outbox-applied").takeIf(File::exists) }
            val saved = requireNotNull(PairingStore(context).current())
            val exact = http.post(saved.origin, DeskEndpoint.FINISH, original, saved)
            val firstTime = JSONObject(original.toString()).put("ereignis", UUID.randomUUID().toString())
            val rejected = http.post(saved.origin, DeskEndpoint.FINISH, firstTime, saved)
            assertTrue(exact.acknowledged())
            assertEquals(409, rejected.status)
            assertEquals("geschlossen", JSONObject(rejected.body).getString("fehler"))
            writeEvidence("outbox-final.json", JSONObject()
                .put("afterApplyExactRetry", exact.status)
                .put("afterApplyFirstSubmission", rejected.status)
                .put("reason", JSONObject(rejected.body).getString("fehler")))
        }
    }

    private suspend fun foreground(model: TimingViewModel, test: suspend () -> Unit) = coroutineScope {
        val foreground = launch(Dispatchers.Main) {
            model.setNetworkAllowed(true)
            model.observeDesk()
        }
        try {
            test()
        } finally {
            foreground.cancelAndJoin()
            model.viewModelScope.cancel()
        }
    }

    private suspend fun <T : Any> waitFor(probe: () -> T?): T = withTimeout(45_000) {
        var value = probe()
        while (value == null) {
            delay(100)
            value = probe()
        }
        value
    }

    private fun writeEvidence(name: String, evidence: JSONObject) {
        val temp = File(context.filesDir, "$name.tmp")
        temp.writeText(evidence.toString())
        check(temp.renameTo(File(context.filesDir, name)))
    }

    private inline fun <T> TimingDatabase.useDatabase(block: (TimingDatabase) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    private fun event(lane: Int) = OutboxEvent(
        UUID.randomUUID(), pairing.generation, origin.value, attempt, lane, EventKind.FINISH, 250_000,
        UUID.randomUUID(), UUID.randomUUID(),
    )
}
