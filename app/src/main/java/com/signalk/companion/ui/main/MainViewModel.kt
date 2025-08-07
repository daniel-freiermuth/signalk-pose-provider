package com.signalk.companion.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signalk.companion.data.model.LocationData
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SignalKTransmitter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val serverAddress: String = "192.168.1.100:3000",
    val locationData: LocationData? = null,
    val sensorData: SensorData? = null,
    val error: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val locationService: LocationService,
    private val signalKTransmitter: SignalKTransmitter
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    init {
        // Observe location updates
        viewModelScope.launch {
            locationService.locationUpdates.collect { locationData ->
                _uiState.update { it.copy(locationData = locationData) }
            }
        }
        
        // Observe service connection status
        viewModelScope.launch {
            signalKTransmitter.connectionStatus.collect { isConnected ->
                _uiState.update { it.copy(isConnected = isConnected) }
            }
        }
    }
    
    fun updateServerAddress(address: String) {
        _uiState.update { it.copy(serverAddress = address) }
    }
    
    fun startStreaming(context: Context) {
        viewModelScope.launch {
            try {
                // Configure SignalK service
                signalKTransmitter.configure(_uiState.value.serverAddress)
                
                // Start location service
                locationService.startLocationUpdates(context)
                
                // Start SignalK service
                signalKTransmitter.startStreaming()
                
                _uiState.update { it.copy(isStreaming = true, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    fun stopStreaming() {
        viewModelScope.launch {
            locationService.stopLocationUpdates()
            signalKTransmitter.stopStreaming()
            _uiState.update { it.copy(isStreaming = false) }
        }
    }
}
