package de.df.jauswertung.zeitnahme.android.delivery

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

internal class DeliveryLifecycle {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun request() {
        requests.trySend(Unit)
    }

    suspend fun replay(send: suspend () -> Boolean) {
        request()
        for (signal in requests) {
            for (attempt in 1..3) {
                if (!send()) break
                if (attempt < 3) delay(attempt * 500L)
            }
        }
    }
}
