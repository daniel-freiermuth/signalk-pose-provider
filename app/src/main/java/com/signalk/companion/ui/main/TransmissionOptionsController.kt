package com.signalk.companion.ui.main

import android.content.Context
import android.content.Intent
import com.signalk.companion.service.SignalKStreamingService
import com.signalk.companion.util.StreamingSettings
import com.signalk.companion.util.TransmissionSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The main screen's data-transmission options: what is sent to the SignalK server and how
 * often. Each change is reflected in [MainUiState], persisted, and pushed to the streaming
 * service when it is running.
 *
 * Owned by [MainViewModel], which supplies its UI state.
 */
class TransmissionOptionsController(
    private val applicationContext: Context,
    private val uiState: MutableStateFlow<MainUiState>
) {

    fun updateSendLocation(enabled: Boolean) {
        uiState.update { it.copy(sendLocation = enabled) }
        TransmissionSettings.setSendLocation(applicationContext, enabled)
        sendConfigUpdateToService()
    }

    fun updateSendHeading(enabled: Boolean) {
        uiState.update { it.copy(sendHeading = enabled) }
        TransmissionSettings.setSendHeading(applicationContext, enabled)
        sendConfigUpdateToService()
    }

    fun updateSendPressure(enabled: Boolean) {
        uiState.update { it.copy(sendPressure = enabled) }
        TransmissionSettings.setSendPressure(applicationContext, enabled)
        sendConfigUpdateToService()
    }

    fun updateLocationIntervalMs(intervalMs: Long) {
        uiState.update { it.copy(locationIntervalMs = intervalMs) }
        StreamingSettings.setLocationIntervalMs(applicationContext, intervalMs)
        sendConfigUpdateToService()
    }

    fun updateSensorIntervalMs(intervalMs: Long) {
        uiState.update { it.copy(sensorIntervalMs = intervalMs) }
        StreamingSettings.setSensorIntervalMs(applicationContext, intervalMs)
        sendConfigUpdateToService()
    }

    /** Push the options to a running service; a later start carries them in its intent. */
    private fun sendConfigUpdateToService() {
        val state = uiState.value
        if (state.isStreaming) {
            val intent = Intent(applicationContext, SignalKStreamingService::class.java).apply {
                action = SignalKStreamingService.ACTION_UPDATE_CONFIG
                putExtra(SignalKStreamingService.EXTRA_LOCATION_RATE, state.locationIntervalMs)
                putExtra(SignalKStreamingService.EXTRA_SENSOR_RATE, state.sensorIntervalMs.toInt())
                putExtra(SignalKStreamingService.EXTRA_SEND_LOCATION, state.sendLocation)
                putExtra(SignalKStreamingService.EXTRA_SEND_HEADING, state.sendHeading)
                putExtra(SignalKStreamingService.EXTRA_SEND_PRESSURE, state.sendPressure)
            }
            applicationContext.sendToStreamingService(intent)
        }
    }
}
