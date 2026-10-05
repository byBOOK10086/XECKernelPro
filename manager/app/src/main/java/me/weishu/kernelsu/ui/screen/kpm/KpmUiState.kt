package me.weishu.kernelsu.ui.screen.kpm

import androidx.compose.runtime.Immutable

sealed interface KpmEffect {
    data class SnackBar(
        val message: String,
    ) : KpmEffect
}

@Immutable
data class KpmUiState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val isFlashing: Boolean = false,
    val active: Boolean = false,
    val version: String = "",
    val modules: List<String> = emptyList(),
)