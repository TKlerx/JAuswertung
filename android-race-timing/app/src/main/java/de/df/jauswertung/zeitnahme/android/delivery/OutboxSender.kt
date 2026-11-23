package de.df.jauswertung.zeitnahme.android.delivery

import de.df.jauswertung.zeitnahme.android.net.DeskEndpoint
import de.df.jauswertung.zeitnahme.android.net.DeskOrigin
import de.df.jauswertung.zeitnahme.android.net.DeskResponse
import de.df.jauswertung.zeitnahme.android.storage.DeliveryStatus
import de.df.jauswertung.zeitnahme.android.storage.DeskPairing
import de.df.jauswertung.zeitnahme.android.storage.EventKind
import de.df.jauswertung.zeitnahme.android.storage.OutboxEvent
import de.df.jauswertung.zeitnahme.android.storage.TimingOutbox
import java.io.IOException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject

internal class OutboxSender(
    private val store: TimingOutbox,
    private val currentPairing: suspend () -> DeskPairing?,
    private val expirePairing: suspend (UUID) -> Unit,
    private val post: suspend (DeskOrigin, DeskEndpoint, DeskPairing, JSONObject) -> DeskResponse,
) {
    private val mutex = Mutex()

    suspend fun sendPending(): Boolean = mutex.withLock {
        for (event in store.pending()) {
            val pairing = currentPairing()
            if (pairing == null || pairing.expired || pairing.generation != event.pairingGeneration ||
                pairing.origin.value != event.deskOrigin
            ) {
                store.blockPairing(event.pairingGeneration, "pairing_unavailable")
                continue
            }
            val endpoint = when (event.kind) {
                EventKind.FINISH -> DeskEndpoint.FINISH
                EventKind.STOPWATCH -> DeskEndpoint.STOPWATCH
            }
            val response = try {
                post(pairing.origin, endpoint, pairing, event.payload())
            } catch (_: SecurityException) {
                store.recordSend(event.id, DeliveryStatus.PENDING, "desk_network_permission")
                return@withLock true
            } catch (_: SSLException) {
                store.recordSend(event.id, DeliveryStatus.PENDING, "desk_tls_failed")
                return@withLock true
            } catch (_: IOException) {
                store.recordSend(event.id, DeliveryStatus.PENDING, "desk_unreachable")
                return@withLock true
            }
            when {
                response.status == 401 -> {
                    store.recordSend(event.id, DeliveryStatus.BLOCKED, "desk_session_expired")
                    store.blockPairing(event.pairingGeneration, "desk_session_expired")
                    expirePairing(event.pairingGeneration)
                    return@withLock store.pending().isNotEmpty()
                }
                response.acknowledged() ->
                    store.recordSend(event.id, DeliveryStatus.ACCEPTED, null)
                response.status in 400..499 && response.status !in setOf(408, 429) ->
                    store.recordSend(event.id, DeliveryStatus.REJECTED, response.json()?.optString("fehler")
                        ?.takeIf(String::isNotBlank) ?: "request_rejected")
                else -> {
                    store.recordSend(event.id, DeliveryStatus.PENDING, "desk_response_unconfirmed")
                    return@withLock true
                }
            }
        }
        false
    }
}

internal fun OutboxEvent.payload(): JSONObject {
    val payload = JSONObject()
        .put("versuch", attemptId.toString())
        .put("ereignis", id.toString())
        .put("bahn", lane)
    return when (kind) {
        EventKind.FINISH -> payload
            .put("zeit", timeMicros)
            .put("epoche", requireNotNull(clockEpoch).toString())
            .put("schaetzung", requireNotNull(estimateId).toString())
        EventKind.STOPWATCH -> payload.put("dauer", timeMicros)
    }
}

internal fun DeskResponse.acknowledged(): Boolean = status == 200 && json()?.opt("angenommen") == true

private fun DeskResponse.json(): JSONObject? = try {
    JSONObject(body)
} catch (_: JSONException) {
    null
}
