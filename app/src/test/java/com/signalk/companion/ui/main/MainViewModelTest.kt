package com.signalk.companion.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.signalk.companion.data.model.AuthState
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.replay.RecordingSession
import com.signalk.companion.service.AttitudeEngine
import com.signalk.companion.service.AuthenticationService
import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SensorService
import com.signalk.companion.service.SignalKTransmitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val context: Context = mock(Context::class.java)
    private val locationService: LocationService = mock(LocationService::class.java)
    private val recordingSession: RecordingSession = mock(RecordingSession::class.java)

    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())

        // sendToStreamingService treats a null component as "service not declared" and throws.
        `when`(context.startService(any<Intent>())).thenReturn(mock(ComponentName::class.java))

        `when`(locationService.locationUpdates).thenReturn(MutableStateFlow(null))
        // A recording is in progress, so toggleRecording() sends the stop command.
        `when`(recordingSession.status)
            .thenReturn(MutableStateFlow(RecordingSession.Status(isRecording = true)))

        val sensorService = mock(SensorService::class.java)
        `when`(sensorService.sensorData).thenReturn(MutableStateFlow(SensorData()))
        val transmitter = mock(SignalKTransmitter::class.java)
        `when`(transmitter.connectionStatus).thenReturn(MutableStateFlow(false))
        `when`(transmitter.lastSentMessage).thenReturn(MutableStateFlow(null))
        `when`(transmitter.authenticationError).thenReturn(MutableStateFlow(null))
        val authenticationService = mock(AuthenticationService::class.java)
        `when`(authenticationService.authState).thenReturn(MutableStateFlow(AuthState()))
        val attitudeEngine = mock(AttitudeEngine::class.java)
        `when`(attitudeEngine.state).thenReturn(MutableStateFlow(null))

        viewModel = MainViewModel(
            context,
            locationService,
            sensorService,
            transmitter,
            authenticationService,
            recordingSession,
            attitudeEngine
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // The service's stopRecording() releases the shared LocationService whenever streaming is
    // not using it - including the registration this screen made for its own display. The
    // screen must claim GNSS back, or the location card freezes until the next ON_RESUME.
    @Test
    fun `stopping a recording in the foreground restarts GNSS for the display`() = runTest {
        advanceUntilIdle()
        viewModel.onAppForeground()
        advanceUntilIdle()
        clearInvocations(locationService)

        viewModel.toggleRecording()
        advanceUntilIdle()

        verify(locationService).startLocationUpdates(
            context,
            viewModel.uiState.value.locationIntervalMs
        )
    }

    @Test
    fun `stopping a recording in the background leaves GNSS off`() = runTest {
        advanceUntilIdle()
        viewModel.onAppForeground()
        advanceUntilIdle()
        viewModel.onAppBackground()
        advanceUntilIdle()
        clearInvocations(locationService)

        viewModel.toggleRecording()
        advanceUntilIdle()

        verify(locationService, never()).startLocationUpdates(
            context,
            viewModel.uiState.value.locationIntervalMs
        )
    }
}
