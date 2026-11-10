package de.schee.wetter.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


/** Zustand des Update-Knopfs in den Einstellungen. */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val release: Release) : UpdateUi
    data class Downloading(val release: Release, val progress: Float) : UpdateUi
    data class Installing(val release: Release) : UpdateUi
    data class NeedsPermission(val release: Release) : UpdateUi
    data class Error(val message: String) : UpdateUi
}

object UpdateState {
    private val _ui = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val ui: StateFlow<UpdateUi> = _ui

    fun set(value: UpdateUi) { _ui.value = value }
    fun failed(message: String) { _ui.value = UpdateUi.Error(message) }
}
