package de.df.jauswertung.zeitnahme.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import de.df.jauswertung.zeitnahme.android.clock.monotonicMicros
import de.df.jauswertung.zeitnahme.android.storage.DeskPairing
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class DeskEndpoint(val path: String) {
    PAIR("/api/paaren"),
    HEAT("/api/lauf"),
    CLOCK("/api/uhr"),
    PROBE("/api/probe"),
    FINISH("/api/ziel"),
    STOPWATCH("/api/stoppuhr"),
}

data class DeskResponse(val status: Int, val body: String)

class DeskHttp(context: Context) {
    private val connectivity = requireNotNull(
        context.applicationContext.getSystemService(ConnectivityManager::class.java),
    )

    suspend fun post(
        origin: DeskOrigin,
        endpoint: DeskEndpoint,
        request: JSONObject,
        pairing: DeskPairing? = null,
        onSent: ((Long) -> Unit)? = null,
        onReceived: ((Long) -> Unit)? = null,
    ): DeskResponse = withContext(Dispatchers.IO) {
        require(
            if (endpoint == DeskEndpoint.PAIR) pairing == null
            else pairing != null && pairing.origin == origin && !pairing.expired,
        )
        val url = URI.create(origin.value + endpoint.path).toURL()
        val connection = (localWifi()?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4_500
            connection.readTimeout = 4_500
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            pairing?.let { connection.setRequestProperty("Authorization", "Bearer ${it.bearer}") }
            val bytes = request.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            if (onSent != null) {
                connection.connect()
                onSent(monotonicMicros())
            }
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            onReceived?.invoke(monotonicMicros())
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            DeskResponse(status, input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun localWifi(): Network? {
        val active = connectivity.activeNetwork
        return active?.takeIf(::isWifi) ?: connectivity.allNetworks.firstOrNull(::isWifi)
    }

    internal fun networkPath(): Network? = localWifi() ?: connectivity.activeNetwork

    private fun isWifi(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
}
