package de.df.jauswertung.zeitnahme.android.ui

import android.app.Application
import android.database.SQLException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.df.jauswertung.zeitnahme.android.capture.FinishCapture
import de.df.jauswertung.zeitnahme.android.capture.StopwatchCapture
import de.df.jauswertung.zeitnahme.android.clock.ACQUISITION_MS
import de.df.jauswertung.zeitnahme.android.clock.ClockEstimate
import de.df.jauswertung.zeitnahme.android.clock.ClockScope
import de.df.jauswertung.zeitnahme.android.clock.ClockSync
import de.df.jauswertung.zeitnahme.android.clock.monotonicMicros
import de.df.jauswertung.zeitnahme.android.clock.processClockEpoch
import de.df.jauswertung.zeitnahme.android.delivery.OutboxSender
import de.df.jauswertung.zeitnahme.android.delivery.DeliveryLifecycle
import de.df.jauswertung.zeitnahme.android.net.DeskEndpoint
import de.df.jauswertung.zeitnahme.android.net.DeskFailure
import de.df.jauswertung.zeitnahme.android.net.DeskHeat
import de.df.jauswertung.zeitnahme.android.net.DeskHttp
import de.df.jauswertung.zeitnahme.android.net.DeskOrigin
import de.df.jauswertung.zeitnahme.android.net.DeskProblem
import de.df.jauswertung.zeitnahme.android.net.DeskResponse
import de.df.jauswertung.zeitnahme.android.net.DeskSession
import de.df.jauswertung.zeitnahme.android.net.HeatState
import de.df.jauswertung.zeitnahme.android.storage.DeliveryStatus
import de.df.jauswertung.zeitnahme.android.storage.DeskPairing
import de.df.jauswertung.zeitnahme.android.storage.OutboxEvent
import de.df.jauswertung.zeitnahme.android.storage.PairingStore
import de.df.jauswertung.zeitnahme.android.storage.TimingDatabase
import de.df.jauswertung.zeitnahme.android.storage.rawTimingCsv
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

data class TimingScreenState(
    val networkAllowed: Boolean = false,
    val pairedName: String? = null,
    val loading: Boolean = true,
    val pairing: Boolean = false,
    val heat: DeskHeat? = null,
    val problem: DeskProblem? = null,
    val syncValid: Boolean = false,
    val capturing: Set<Int> = emptySet(),
    val delivery: Map<Int, OutboxEvent> = emptyMap(),
    val unresolved: List<OutboxEvent> = emptyList(),
    val captureStorageFailed: Boolean = false,
    val hasCaptures: Boolean = false,
) {
    fun canCapture(lane: Int, phoneTiming: Boolean): Boolean =
        heat?.state == HeatState.LAEUFT && lane !in capturing && lane !in delivery &&
            (!phoneTiming || syncValid)
}

class TimingViewModel internal constructor(
    application: Application,
    private val http: DeskHttp,
    private val post: suspend (DeskOrigin, DeskEndpoint, JSONObject, DeskPairing?, ((Long) -> Unit)?,
        ((Long) -> Unit)?) -> DeskResponse = http::post,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, DeskHttp(application))

    private val pairings = PairingStore(application)
    private val session = DeskSession(http, pairings)
    private val outbox = TimingDatabase.open(application).outbox()
    private val finish = FinishCapture(outbox::enqueue)
    private val stopwatch = StopwatchCapture(outbox::enqueue)
    private val sender = OutboxSender(outbox, pairings::current, pairings::expire) { origin, endpoint, pairing, json ->
        post(origin, endpoint, json, pairing, null, null)
    }
    private val delivery = DeliveryLifecycle()
    private val mutable = MutableStateFlow(TimingScreenState())
    val state = mutable.asStateFlow()
    private var pairing: DeskPairing? = null
    private var estimate: ClockEstimate? = null
    private var outboxEvents = emptyList<OutboxEvent>()

    init {
        viewModelScope.launch {
            try {
                outbox.observe().collect {
                    outboxEvents = it
                    updateLaneStatus()
                }
            } catch (_: SQLException) {
                mutable.update { it.copy(captureStorageFailed = true) }
            }
        }
    }

    fun setNetworkAllowed(allowed: Boolean) {
        val granted = allowed && !mutable.value.networkAllowed
        mutable.update { it.copy(networkAllowed = allowed) }
        if (granted) delivery.request()
    }

    fun retryPending() = delivery.request()

    internal suspend fun exportRawTiming(): String = outbox.observe().first().rawTimingCsv()

    suspend fun observeDesk(): Unit = coroutineScope {
        launch { delivery.replay { mutable.value.networkAllowed && sendPending() } }
        if (mutable.value.loading) {
            try {
                pairing = session.restore()
                mutable.update { it.copy(loading = false, pairedName = pairing?.displayName) }
            } catch (failure: DeskFailure) {
                mutable.update { it.copy(loading = false, problem = failure.problem) }
            }
        }
        launch { sampleClock() }
        launch {
            while (true) {
                updateSyncStatus()
                delay(ACQUISITION_MS)
            }
        }
        while (true) {
            val current = mutable.value
            if (current.networkAllowed && current.pairedName != null) refresh()
            delay(600)
        }
    }

    fun pair(address: String, name: String, code: String) {
        if (mutable.value.pairing || !mutable.value.networkAllowed || mutable.value.pairedName != null) return
        val origin = try {
            DeskOrigin.parse(address)
        } catch (_: IllegalArgumentException) {
            mutable.update { it.copy(problem = DeskProblem.INVALID_ADDRESS) }
            return
        }
        mutable.update { it.copy(pairing = true, problem = null) }
        viewModelScope.launch {
            try {
                val saved = session.pair(origin, name, code)
                pairing = saved
                mutable.update { it.copy(pairedName = saved.displayName, heat = null) }
                refresh()
            } catch (failure: DeskFailure) {
                mutable.update { it.copy(problem = failure.problem) }
            } finally {
                mutable.update { it.copy(pairing = false) }
            }
        }
    }

    fun captureFinish(attempt: UUID, lane: Int) {
        val capturedAt = monotonicMicros()
        val credentials = pairing ?: return
        val heat = mutable.value.heat?.takeIf { it.attempt == attempt } ?: return
        val clock = estimate ?: return
        val scope = ClockScope(credentials.generation, attempt, processClockEpoch)
        saveCapture(attempt, credentials.generation, lane) {
            finish.record(credentials.origin, scope, heat, clock, lane, capturedAt)
        }
    }

    fun captureStopwatch(attempt: UUID, lane: Int, duration: Long) {
        val credentials = pairing ?: return
        val heat = mutable.value.heat?.takeIf { it.attempt == attempt } ?: return
        saveCapture(attempt, credentials.generation, lane) {
            stopwatch.record(credentials.origin, credentials.generation, attempt, heat, lane, duration)
        }
    }

    private fun saveCapture(attempt: UUID, generation: UUID, lane: Int, record: suspend () -> OutboxEvent?) {
        val current = mutable.value
        if (lane in current.capturing || lane in current.delivery) return
        mutable.update { it.copy(capturing = it.capturing + lane, captureStorageFailed = false) }
        viewModelScope.launch {
            try {
                val event = record()
                if (event != null) {
                    if (outboxEvents.none { it.id == event.id }) outboxEvents = outboxEvents + event
                    updateLaneStatus()
                    delivery.request()
                }
            } catch (_: SQLException) {
                mutable.update { it.copy(captureStorageFailed = true) }
            } finally {
                if (pairing?.generation == generation && mutable.value.heat?.attempt == attempt) {
                    mutable.update { it.copy(capturing = it.capturing - lane) }
                }
            }
        }
    }

    private suspend fun sendPending(): Boolean {
        try {
            return sender.sendPending()
        } catch (_: SQLException) {
            mutable.update { it.copy(captureStorageFailed = true) }
        } catch (_: IOException) {
            mutable.update { it.copy(captureStorageFailed = true) }
        } catch (_: GeneralSecurityException) {
            mutable.update { it.copy(captureStorageFailed = true) }
        } catch (_: JSONException) {
            mutable.update { it.copy(captureStorageFailed = true) }
        }
        return false
    }

    private suspend fun sampleClock() {
        var nextSample = 0L
        var selectedScope: ClockScope? = null
        var path = http.networkPath()
        while (true) {
            val current = mutable.value
            val scope = currentScope()
            val credentials = pairing
            val heat = current.heat
            val currentPath = http.networkPath()
            if (scope != selectedScope || path != currentPath) nextSample = 0
            if (path != currentPath) delivery.request()
            selectedScope = scope
            path = currentPath
            if (scope != null && credentials != null && current.networkAllowed && heat?.syncRequired == true &&
                heat.state in syncStates && monotonicMicros() >= nextSample
            ) {
                val sample = ClockSync(::monotonicMicros) { endpoint, body, sent, received ->
                    post(credentials.origin, endpoint, body, credentials, sent, received)
                }.sample(scope)
                if (currentScope() == scope) {
                    if (sample != null) estimate = sample
                    updateSyncStatus()
                }
                val interval = if (sample == null || heat.state == HeatState.BEREIT && !heat.ready) ACQUISITION_MS
                else sample.refreshMs.coerceAtMost(1_000)
                nextSample = monotonicMicros() + interval * 1_000
            }
            delay(ACQUISITION_MS)
        }
    }

    private fun currentScope(): ClockScope? {
        val credentials = pairing ?: return null
        val heat = mutable.value.heat ?: return null
        return ClockScope(credentials.generation, heat.attempt, processClockEpoch)
    }

    private fun updateLaneStatus() {
        val scope = currentScope()
        val outcomes = if (scope == null) emptyMap() else outboxEvents
            .filter { it.pairingGeneration == scope.pairing && it.attemptId == scope.attempt }
            .associateBy(OutboxEvent::lane)
        val unresolved = outboxEvents.filter { it.status != DeliveryStatus.ACCEPTED }
        mutable.update { it.copy(delivery = outcomes, unresolved = unresolved, hasCaptures = outboxEvents.isNotEmpty()) }
    }

    private fun updateSyncStatus() {
        val scope = currentScope()
        val sync = scope != null && estimate?.map(scope, monotonicMicros()) != null
        mutable.update { it.copy(syncValid = sync) }
    }

    private suspend fun refresh() {
        try {
            val heat = session.refresh()
            if (mutable.value.problem != null) delivery.request()
            mutable.update {
                if (it.heat?.attempt == heat?.attempt) it.copy(heat = heat, problem = null)
                else it.copy(heat = heat, problem = null, capturing = emptySet(), delivery = emptyMap(),
                    syncValid = false, captureStorageFailed = false)
            }
            updateLaneStatus()
        } catch (failure: DeskFailure) {
            if (failure.problem == DeskProblem.EXPIRED) {
                pairing = null
                estimate = null
            }
            mutable.update {
                if (failure.problem == DeskProblem.EXPIRED) {
                    TimingScreenState(networkAllowed = it.networkAllowed, loading = false,
                        problem = failure.problem, unresolved = it.unresolved)
                } else it.copy(heat = session.lastHeat, problem = failure.problem)
            }
        }
    }

    private val syncStates = setOf(HeatState.BEREIT, HeatState.TISCHSTART, HeatState.FREIGEGEBEN,
        HeatState.START_UNSICHER, HeatState.LAEUFT)
}
