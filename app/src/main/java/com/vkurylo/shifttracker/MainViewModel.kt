package com.vkurylo.shifttracker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vkurylo.shifttracker.domain.model.UserPreferences
import com.vkurylo.shifttracker.domain.model.hasDeductions
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.ui.util.EarningsDisplay
import com.vkurylo.shifttracker.domain.repository.LanguageRepository
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
    languageRepository: LanguageRepository,
    jobRepository: JobRepository,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences?> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Gross or net everywhere, and whether any job has deductions (only then are amounts tagged). */
    val earningsDisplay: StateFlow<EarningsDisplay> = combine(
        preferencesRepository.preferences,
        jobRepository.observeJobs(),
    ) { prefs, jobs -> EarningsDisplay(prefs.earningsMode, showTag = jobs.any { it.hasDeductions }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, EarningsDisplay())

    init {
        viewModelScope.launch { languageRepository.applyDefaultOnFirstLaunch() }
    }
}
