package me.weishu.kernelsu.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.screen.kpm.KpmEffect
import me.weishu.kernelsu.ui.screen.kpm.KpmUiState
import me.weishu.kernelsu.ui.util.flashKpmModule
import me.weishu.kernelsu.ui.util.kpmList
import me.weishu.kernelsu.ui.util.kpmVersion

class KpmViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(KpmUiState())
    val uiState: StateFlow<KpmUiState> = _uiState.asStateFlow()

    private val _kpmEvent = Channel<KpmEffect>(Channel.BUFFERED)
    val kpmEvent: Flow<KpmEffect> = _kpmEvent.receiveAsFlow()

    private var fetchJob: Job? = null

    fun load() {
        fetchJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }
        fetchJob = viewModelScope.launch {
            try {
                val version = withContext(Dispatchers.IO) { kpmVersion() }
                val listRaw = withContext(Dispatchers.IO) { kpmList() }
                val modules = listRaw.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                _uiState.update {
                    it.copy(
                        active = version.isNotEmpty(),
                        version = version,
                        modules = modules,
                        hasLoaded = true,
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun flash(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isFlashing = true) }
            try {
                val result = withContext(Dispatchers.IO) {
                    flashKpmModule(uri, {}, {})
                }
                val res = ksuApp.resources
                val message = if (result.code == 0) {
                    R.string.kpm_flash_success
                } else {
                    R.string.kpm_flash_failed
                }
                emitEffect(KpmEffect.SnackBar(res.getString(message)))
                if (result.code == 0) {
                    load()
                }
            } finally {
                _uiState.update { it.copy(isFlashing = false) }
            }
        }
    }

    fun emitEffect(effect: KpmEffect) {
        _kpmEvent.trySend(effect)
    }
}