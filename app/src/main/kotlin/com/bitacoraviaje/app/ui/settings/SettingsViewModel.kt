package com.bitacoraviaje.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitacoraviaje.core.model.Units
import com.bitacoraviaje.app.settings.UserPreferences
import com.bitacoraviaje.app.settings.UserSettings
import com.bitacoraviaje.core.model.RecordingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(private val prefs: UserPreferences) : ViewModel() {
    val settings: StateFlow<UserSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    fun setUnits(units: Units) = viewModelScope.launch { prefs.setUnits(units) }

    fun setRecordingMode(mode: RecordingMode) = viewModelScope.launch { prefs.setRecordingMode(mode) }

    fun setTranscriptionLanguage(tag: String) = viewModelScope.launch { prefs.setTranscriptionLanguage(tag) }
}
