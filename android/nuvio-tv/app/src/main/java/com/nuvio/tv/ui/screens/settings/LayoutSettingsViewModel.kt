package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.HomeLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LayoutSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {
    val selectedLayout = layoutPreferenceDataStore.selectedLayout.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), HomeLayout.MODERN
    )

    fun selectLayout(layout: HomeLayout) {
        viewModelScope.launch { layoutPreferenceDataStore.setLayout(layout) }
    }
}
