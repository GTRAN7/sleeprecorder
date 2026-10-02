package com.erictran.sleepsounds

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val nights = MutableStateFlow<List<Night>>(emptyList())
    val player = ClipPlayer()

    init {
        // Reload when recording starts or stops, which is when the set of finished nights changes.
        viewModelScope.launch {
            RecorderState.status.map { it.nightId }.distinctUntilChanged().collect { refresh(it) }
        }
        viewModelScope.launch {
            while (true) {
                delay(200)
                player.publish()
            }
        }
    }

    private suspend fun refresh(activeId: String? = RecorderState.status.value.nightId) {
        nights.value = withContext(Dispatchers.IO) { NightStore.loadAll(getApplication(), activeId) }
    }

    fun delete(night: Night) {
        player.stop()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { NightStore.delete(night) }
            refresh()
        }
    }

    fun delete(night: Night, event: SoundEvent) {
        if (player.state.value.clip == event.clip) player.stop()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { NightStore.deleteEvent(night, event) }
            refresh()
        }
    }

    override fun onCleared() {
        player.stop()
    }
}
