package de.df.jauswertung.zeitnahme.android.net

import de.df.jauswertung.zeitnahme.android.storage.DeskPairing
import de.df.jauswertung.zeitnahme.android.storage.PairingStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject

enum class DeskProblem {
    INVALID_ADDRESS,
    INVALID_PAIR_INPUT,
    PAIR_CODE,
    PAIR_UNCONFIRMED,
    EXPIRED,
    LOCAL_NETWORK,
    TLS,
    UNREACHABLE,
    INVALID_RESPONSE,
    STORAGE,
}

class DeskFailure(val problem: DeskProblem) : Exception()

enum class HeatState { BEREIT, TISCHSTART, FREIGEGEBEN, START_UNSICHER, LAEUFT, GESCHLOSSEN, UEBERNOMMEN }

data class AssignedLane(val number: Int, val participant: String, val phoneTiming: Boolean)

data class DeskHeat(
    val attempt: UUID,
    val id: UUID,
    val discipline: String,
    val state: HeatState,
    val ready: Boolean,
    val syncRequired: Boolean,
    val lanes: List<AssignedLane>,
)

class DeskSession internal constructor(
    private val post: suspend (DeskOrigin, DeskEndpoint, JSONObject, DeskPairing?) -> DeskResponse,
    private val readPairing: suspend () -> DeskPairing?,
    private val savePairing: suspend (DeskOrigin, String, String) -> DeskPairing,
    private val expirePairing: suspend (UUID) -> Unit,
) {
    constructor(http: DeskHttp, store: PairingStore) :
        this({ origin, endpoint, json, pairing -> http.post(origin, endpoint, json, pairing) },
            store::current, store::saveSuccessful, store::expire)

    private val mutex = Mutex()
    private var active: DeskPairing? = null
    var lastHeat: DeskHeat? = null
        private set

    suspend fun restore(): DeskPairing? = mutex.withLock {
        val saved = storage(DeskProblem.STORAGE) { readPairing() }
        if (saved?.expired == true) {
            selectPairing(null)
            throw DeskFailure(DeskProblem.EXPIRED)
        }
        selectPairing(saved)
        saved
    }

    suspend fun pair(origin: DeskOrigin, name: String, code: String): DeskPairing = mutex.withLock {
        val label = name.trim()
        val invitation = code.trim()
        if (label.length !in 1..60 || invitation.length !in 1..64) {
            throw DeskFailure(DeskProblem.INVALID_PAIR_INPUT)
        }
        val response = request(origin, DeskEndpoint.PAIR, JSONObject().put("code", invitation).put("name", label), null)
        if (response.status == 403 && response.json()?.optString("fehler") == "code") {
            throw DeskFailure(DeskProblem.PAIR_CODE)
        }
        val token = response.json()?.opt("zugang") as? String
        if (response.status != 200 || token.isNullOrEmpty() || token.any { it !in '!'..'~' }) {
            throw DeskFailure(DeskProblem.PAIR_UNCONFIRMED)
        }
        storage(DeskProblem.PAIR_UNCONFIRMED) { savePairing(origin, label, token) }.also(::selectPairing)
    }

    suspend fun refresh(): DeskHeat? = mutex.withLock {
        val pairing = active ?: throw DeskFailure(DeskProblem.EXPIRED)
        val response = request(pairing.origin, DeskEndpoint.HEAT, JSONObject(), pairing)
        if (response.status == 401) {
            selectPairing(null)
            storage(DeskProblem.STORAGE) { expirePairing(pairing.generation) }
            throw DeskFailure(DeskProblem.EXPIRED)
        }
        if (response.status != 200) {
            throw DeskFailure(if (response.status >= 500) DeskProblem.UNREACHABLE else DeskProblem.INVALID_RESPONSE)
        }
        val json = response.json() ?: throw DeskFailure(DeskProblem.INVALID_RESPONSE)
        val heat = try {
            if (json.has("versuch")) json.toHeat() else {
                json.getString("name")
                null
            }
        } catch (_: JSONException) {
            throw DeskFailure(DeskProblem.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            throw DeskFailure(DeskProblem.INVALID_RESPONSE)
        }
        lastHeat = heat
        heat
    }

    private fun selectPairing(pairing: DeskPairing?) {
        if (active?.generation != pairing?.generation) lastHeat = null
        active = pairing
    }

    private suspend fun request(
        origin: DeskOrigin,
        endpoint: DeskEndpoint,
        json: JSONObject,
        pairing: DeskPairing?,
    ): DeskResponse = try {
        post(origin, endpoint, json, pairing)
    } catch (_: SSLException) {
        throw DeskFailure(DeskProblem.TLS)
    } catch (_: SecurityException) {
        throw DeskFailure(DeskProblem.LOCAL_NETWORK)
    } catch (_: IOException) {
        throw DeskFailure(if (endpoint == DeskEndpoint.PAIR) DeskProblem.PAIR_UNCONFIRMED else DeskProblem.UNREACHABLE)
    }

    private suspend fun <T> storage(problem: DeskProblem, action: suspend () -> T): T = try {
        action()
    } catch (_: IOException) {
        throw DeskFailure(problem)
    } catch (_: GeneralSecurityException) {
        throw DeskFailure(problem)
    } catch (_: JSONException) {
        throw DeskFailure(problem)
    } catch (_: IllegalArgumentException) {
        throw DeskFailure(problem)
    }

    private fun DeskResponse.json(): JSONObject? = try {
        JSONObject(body)
    } catch (_: JSONException) {
        null
    }

    private fun JSONObject.toHeat(): DeskHeat {
        val lanesJson = getJSONArray("bahnen")
        val lanes = List(lanesJson.length()) { index ->
            val lane = lanesJson.getJSONObject(index)
            AssignedLane(lane.getInt("nummer"), lane.getString("teilnehmer"), lane.getBoolean("telefonzeit"))
        }.sortedBy(AssignedLane::number)
        require(lanes.all { it.number > 0 } && lanes.distinctBy(AssignedLane::number).size == lanes.size)
        return DeskHeat(
            UUID.fromString(getString("versuch")),
            UUID.fromString(getString("lauf")),
            getString("disziplin"),
            HeatState.valueOf(getString("zustand")),
            getBoolean("bereit"),
            getBoolean("abgleich"),
            lanes,
        )
    }
}
