package de.df.jauswertung.zeitnahme.android.delivery

import de.df.jauswertung.zeitnahme.android.net.DeskEndpoint
import de.df.jauswertung.zeitnahme.android.net.DeskHttp
import de.df.jauswertung.zeitnahme.android.net.DeskResponse
import de.df.jauswertung.zeitnahme.android.storage.DeliveryStatus
import de.df.jauswertung.zeitnahme.android.storage.EventKind
import de.df.jauswertung.zeitnahme.android.storage.OutboxEvent
import de.df.jauswertung.zeitnahme.android.storage.PairingStore
import de.df.jauswertung.zeitnahme.android.storage.TimingOutbox
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject

class OutboxSender(
    private val store: TimingOutbox,
    private val pairings: PairingStore,
    private val http: DeskHttp,
) {
    private val mutex = Mutex()

    suspend fun sendPending() = mutex.withLock {
        for (event in store.pending()) {
            val pairing = pairings.current()
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
                http.post(pairing.origin, endpoint, event.payload(), pairing)
            } catch (_: SecurityException) {
                store.recordSend(event.id, DeliveryStatus.PENDING, "desk_network_permission")
                break
            } catch (_: SSLException) {
                store.recordSend(event.id, DeliveryStatus.PENDING, "desk_tls_failed")
                break
            } catch (_: IOException) {
                store.recordSend(event.id, DeliveryStatus.PENDING, "desk_unreachable")
                break
            }
            val body = response.json()
            when {
                response.status == 401 -> {
                    pairings.expire(event.pairingGeneration)
                    store.recordSend(event.id, DeliveryStatus.BLOCKED, "desk_session_expired")
                    store.blockPairing(event.pairingGeneration, "desk_session_expired")
                    break
                }
                response.status == 200 && body?.opt("angenommen") == true ->
                    store.recordSend(event.id, DeliveryStatus.ACCEPTED, null)
                response.status in 400..499 && response.status !in setOf(408, 429) ->
                    store.recordSend(event.id, DeliveryStatus.REJECTED, body?.optString("fehler")
                        ?.takeIf(String::isNotBlank) ?: "request_rejected")
                else -> {
                    store.recordSend(event.id, DeliveryStatus.PENDING, "desk_response_unconfirmed")
                    break
                }
            }
        }
    }

    private fun OutboxEvent.payload(): JSONObject {
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

    private fun DeskResponse.json(): JSONObject? = try {
        JSONObject(body)
    } catch (_: JSONException) {
        null
    }
}
