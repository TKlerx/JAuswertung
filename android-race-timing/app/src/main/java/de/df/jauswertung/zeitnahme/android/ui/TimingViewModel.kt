package de.df.jauswertung.zeitnahme.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.df.jauswertung.zeitnahme.android.net.DeskFailure
import de.df.jauswertung.zeitnahme.android.net.DeskHeat
import de.df.jauswertung.zeitnahme.android.net.DeskHttp
import de.df.jauswertung.zeitnahme.android.net.DeskOrigin
import de.df.jauswertung.zeitnahme.android.net.DeskProblem
import de.df.jauswertung.zeitnahme.android.net.DeskSession
import de.df.jauswertung.zeitnahme.android.storage.PairingStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimingScreenState(
    val networkAllowed: Boolean = false,
    val pairedName: String? = null,
    val loading: Boolean = true,
    val pairing: Boolean = false,
    val heat: DeskHeat? = null,
    val problem: DeskProblem? = null,
)

class TimingViewModel(application: Application) : AndroidViewModel(application) {
    private val session = DeskSession(DeskHttp(application), PairingStore(application))
    private val mutable = MutableStateFlow(TimingScreenState())
    val state = mutable.asStateFlow()

    fun setNetworkAllowed(allowed: Boolean) {
        mutable.update { it.copy(networkAllowed = allowed) }
    }

    suspend fun observeDesk() {
        if (mutable.value.loading) {
            try {
                val pairing = session.restore()
                mutable.update { it.copy(loading = false, pairedName = pairing?.displayName) }
            } catch (failure: DeskFailure) {
                mutable.update { it.copy(loading = false, problem = failure.problem) }
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
                val pairing = session.pair(origin, name, code)
                mutable.update { it.copy(pairedName = pairing.displayName, heat = null) }
                refresh()
            } catch (failure: DeskFailure) {
                mutable.update { it.copy(problem = failure.problem) }
            } finally {
                mutable.update { it.copy(pairing = false) }
            }
        }
    }

    private suspend fun refresh() {
        try {
            val heat = session.refresh()
            mutable.update { it.copy(heat = heat, problem = null) }
        } catch (failure: DeskFailure) {
            mutable.update {
                if (failure.problem == DeskProblem.EXPIRED) {
                    it.copy(pairedName = null, heat = null, problem = failure.problem)
                } else it.copy(heat = session.lastHeat, problem = failure.problem)
            }
        }
    }
}
