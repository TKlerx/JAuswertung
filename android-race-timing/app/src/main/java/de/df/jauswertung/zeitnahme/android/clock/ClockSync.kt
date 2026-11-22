package de.df.jauswertung.zeitnahme.android.clock

import android.os.SystemClock
import de.df.jauswertung.zeitnahme.android.net.DeskEndpoint
import de.df.jauswertung.zeitnahme.android.net.DeskResponse
import java.io.IOException
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

internal val processClockEpoch: UUID = UUID.randomUUID()
internal fun monotonicMicros(): Long = SystemClock.elapsedRealtimeNanos() / 1_000
internal const val ACQUISITION_MS = 200L
private const val PROBE_EXPIRY_US = 5_000_000L
private const val MICROS_PER_SECOND = 1_000_000L
private const val CAPTURE_BUDGET_US = 10_000L

internal data class ClockScope(val pairing: UUID, val attempt: UUID, val epoch: UUID)

internal data class ClockEstimate(
    val scope: ClockScope,
    val deskEpoch: UUID,
    val id: UUID,
    val offset: Long,
    val uncertainty: Long,
    val reference: Long,
    val driftPpm: Long,
    val refreshMs: Long,
) {
    init {
        require(uncertainty >= 0 && driftPpm in 0 until MICROS_PER_SECOND && refreshMs >= ACQUISITION_MS)
    }

    fun map(captureScope: ClockScope, phoneMicros: Long): Long? {
        if (scope != captureScope || phoneMicros < 0) return null
        return try {
            val mapped = Math.addExact(phoneMicros, offset)
            val delta = Math.subtractExact(mapped, reference)
            val age = if (delta >= 0) delta else Math.negateExact(delta)
            val drift = Math.multiplyExact(age, driftPpm)
            val rounded = drift / MICROS_PER_SECOND + if (drift % MICROS_PER_SECOND == 0L) 0 else 1
            mapped.takeIf { Math.addExact(uncertainty, rounded) <= CAPTURE_BUDGET_US }
        } catch (_: ArithmeticException) {
            null
        }
    }
}

internal class ClockSync(
    private val now: () -> Long,
    private val post: suspend (DeskEndpoint, JSONObject, ((Long) -> Unit)?, ((Long) -> Unit)?) -> DeskResponse,
) {
    suspend fun sample(scope: ClockScope): ClockEstimate? {
        return try {
            var sent = -1L
            var received = -1L
            val epoch = scope.epoch.toString()
            val offer = post(
                DeskEndpoint.CLOCK,
                JSONObject().put("versuch", scope.attempt.toString()).put("epoche", epoch),
                { sent = it }, { received = it },
            ).objectOrNull() ?: return null
            val deskEpoch = offer.uuid("schreibtischEpoche")
            val completed = now()
            if (sent < 0 || received < sent || completed < received || completed - sent > PROBE_EXPIRY_US ||
                offer.integer("schreibtischGesendet") < offer.integer("schreibtischEmpfangen")
            ) return null
            val estimate = post(
                DeskEndpoint.PROBE,
                JSONObject().put("probe", offer.uuid("probe").toString()).put("epoche", epoch)
                    .put("telefonGesendet", sent).put("telefonEmpfangen", received),
                null, null,
            ).objectOrNull() ?: return null
            ClockEstimate(
                scope, deskEpoch, estimate.uuid("id"), estimate.integer("versatz"),
                estimate.integer("unsicherheit"), estimate.integer("bezugszeit"), estimate.integer("driftPpm"),
                estimate.integer("auffrischungMs"),
            )
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun DeskResponse.objectOrNull(): JSONObject? = if (status == 200) JSONObject(body) else null

    private fun JSONObject.uuid(key: String): UUID = UUID.fromString(getString(key))

    private fun JSONObject.integer(key: String): Long = when (val value = get(key)) {
        is Long -> value
        is Int -> value.toLong()
        else -> throw JSONException(key)
    }
}
