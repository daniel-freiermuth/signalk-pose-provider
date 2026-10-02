package com.signalk.companion.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.signalk.companion.data.model.AuthState
import com.signalk.companion.data.model.LocationData
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.replay.RecordingSession
import com.signalk.companion.service.AttitudeEngine
import com.signalk.companion.service.AuthenticationService
import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SensorService
import com.signalk.companion.service.SignalKStreamingService
import com.signalk.companion.service.SignalKTransmitter
import com.signalk.companion.util.verifyCalled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * The main screen's orchestration: binding to the streaming service, starting and stopping
 * a stream, and keeping the foreground sensors running exactly when something needs them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private companion object {
        const val SERVER_URL = "http://192.168.1.10:3000"
        const val USERNAME = "skipper"
        const val PASSWORD = "secret"
        const val SENSOR_RESTART_DELAY_MS = 500L
    }

    /** A bound streaming service: the instance plus the flows it exposes over the binder. */
    private class FakeBoundService {
        val isStreaming = MutableStateFlow(false)
        val messagesSent = MutableStateFlow(0)
        val lastTransmissionTime = MutableStateFlow<Long?>(null)
        val error = MutableStateFlow<String?>(null)
        val service: SignalKStreamingService = mock(SignalKStreamingService::class.java).also {
            `when`(it.isStreaming).thenReturn(isStreaming)
            `when`(it.messagesSent).thenReturn(messagesSent)
            `when`(it.lastTransmissionTime).thenReturn(lastTransmissionTime)
            `when`(it.error).thenReturn(error)
        }
        val binder: SignalKStreamingService.LocalBinder =
            mock(SignalKStreamingService.LocalBinder::class.java).also {
                `when`(it.getService()).thenReturn(service)
            }
    }

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var locationService: LocationService
    private lateinit var sensorService: SensorService
    private lateinit var authenticationService: AuthenticationService
    private lateinit var recordingSession: RecordingSession
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var viewModel: MainViewModel
    private val component = ComponentName("com.signalk.companion", "SignalKStreamingService")

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        context = mock(Context::class.java)
        preferences = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
        `when`(preferences.edit()).thenReturn(mock(SharedPreferences.Editor::class.java))
        stubStoredSettings(serverUrl = SERVER_URL, username = "", password = "")
        // sendToStreamingService fails loudly on a null component (service not in manifest).
        `when`(context.startForegroundService(any(Intent::class.java))).thenReturn(component)
        `when`(context.startService(any(Intent::class.java))).thenReturn(component)
        `when`(
            context.bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt())
        ).thenReturn(true)

        locationService = mock(LocationService::class.java)
        `when`(locationService.locationUpdates).thenReturn(MutableStateFlow<LocationData?>(null))

        sensorService = mock(SensorService::class.java)
        `when`(sensorService.sensorData).thenReturn(MutableStateFlow(SensorData()))

        val transmitter = mock(SignalKTransmitter::class.java)
        `when`(transmitter.connectionStatus).thenReturn(MutableStateFlow(false))
        `when`(transmitter.lastSentMessage).thenReturn(MutableStateFlow<String?>(null))
        `when`(transmitter.authenticationError).thenReturn(MutableStateFlow<String?>(null))

        authenticationService = mock(AuthenticationService::class.java)
        `when`(authenticationService.authState).thenReturn(MutableStateFlow(AuthState()))

        recordingSession = mock(RecordingSession::class.java)
        `when`(recordingSession.status).thenReturn(MutableStateFlow(RecordingSession.Status()))

        val attitudeEngine = mock(AttitudeEngine::class.java)
        `when`(attitudeEngine.state).thenReturn(MutableStateFlow<AttitudeEngine.State?>(null))

        // Created through a ViewModelStore so that clearing the store runs onCleared exactly
        // as the framework does, and cancels viewModelScope between tests.
        viewModelStore = ViewModelStore()
        val factory = viewModelFactory {
            initializer {
                MainViewModel(
                    context,
                    locationService,
                    sensorService,
                    transmitter,
                    authenticationService,
                    recordingSession,
                    attitudeEngine
                )
            }
        }
        viewModel = ViewModelProvider.create(viewModelStore, factory)[MainViewModel::class]
    }

    @AfterEach
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    private fun stubStoredSettings(serverUrl: String, username: String, password: String) {
        `when`(preferences.getString(eq("server_url"), anyString())).thenReturn(serverUrl)
        `when`(preferences.getString(eq("username"), anyString())).thenReturn(username)
        `when`(preferences.getString(eq("password"), anyString())).thenReturn(password)
        `when`(preferences.getString(eq("vessel_id"), anyString())).thenReturn("self")
        `when`(preferences.getBoolean(anyString(), anyBoolean())).thenReturn(true)
        `when`(preferences.getLong(eq("location_interval_ms"), anyLong())).thenReturn(1000L)
        `when`(preferences.getLong(eq("sensor_interval_ms"), anyLong())).thenReturn(250L)
        `when`(preferences.getFloat(anyString(), anyFloat())).thenReturn(0f)
    }

    /** The connection the view model passed to bindService, as the framework would see it. */
    private fun capturedConnection(): ServiceConnection {
        val captor = ArgumentCaptor.forClass(ServiceConnection::class.java)
        verifyCalled(context) { bindService(any(Intent::class.java), captor.capture(), anyInt()) }
        return captor.value
    }

    /** Start streaming and complete the bind with [bound], as the framework does. */
    private fun startStreamingAndBind(bound: FakeBoundService) {
        viewModel.initializeSettings()
        viewModel.startStreaming()
        capturedConnection().onServiceConnected(component, bound.binder)
    }

    // --- startStreaming ---

    @Test
    fun `startStreaming with an unsupported URL reports it and never contacts the service`() =
        runTest {
            stubStoredSettings(serverUrl = "ftp://invalid.server", username = "", password = "")
            viewModel.initializeSettings()

            viewModel.startStreaming()

            assertTrue(
                viewModel.uiState.value.error.orEmpty().startsWith("Invalid server URL"),
                "Unexpected error: ${viewModel.uiState.value.error}"
            )
            verifyCalled(context, never()) {
                bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt())
            }
            verifyCalled(context, never()) { startForegroundService(any(Intent::class.java)) }
        }

    @Test
    fun `startStreaming with a valid URL clears a previous error and starts the service`() =
        runTest {
            stubStoredSettings(serverUrl = "ftp://invalid.server", username = "", password = "")
            viewModel.initializeSettings()
            viewModel.startStreaming()
            stubStoredSettings(serverUrl = SERVER_URL, username = "", password = "")
            viewModel.initializeSettings()

            viewModel.startStreaming()

            assertNull(viewModel.uiState.value.error)
            verifyCalled(context) { startForegroundService(any(Intent::class.java)) }
        }

    @Test
    fun `startStreaming does not bind again while already bound`() = runTest {
        startStreamingAndBind(FakeBoundService())

        viewModel.startStreaming()

        verifyCalled(context, times(1)) {
            bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt())
        }
        verifyCalled(context, times(2)) { startForegroundService(any(Intent::class.java)) }
    }

    // --- Service binding lifecycle ---

    @Test
    fun `binding mirrors the service state into the UI`() = runTest {
        val bound = FakeBoundService()
        startStreamingAndBind(bound)

        bound.isStreaming.value = true
        bound.messagesSent.value = 7
        bound.lastTransmissionTime.value = 1234L
        bound.error.value = "Connection refused"

        val state = viewModel.uiState.value
        assertTrue(state.isStreaming)
        assertEquals(7, state.messagesSent)
        assertEquals(1234L, state.lastTransmissionTime)
        assertEquals("Connection refused", state.error)

        // A cleared service error does not wipe the message the user has not dismissed yet.
        bound.error.value = null
        assertEquals("Connection refused", viewModel.uiState.value.error)
    }

    @Test
    fun `reconnecting to a new service instance stops listening to the old one`() = runTest {
        val first = FakeBoundService()
        val second = FakeBoundService()
        startStreamingAndBind(first)
        val connection = capturedConnection()

        connection.onServiceConnected(component, second.binder)
        second.messagesSent.value = 3
        // Emitted last, so it would win if the first instance's collector were still alive.
        first.messagesSent.value = 99

        assertEquals(3, viewModel.uiState.value.messagesSent)

        // Calibration changes go to the instance that is bound now.
        viewModel.calibration.updateCalibrationAngles(1f, 2f, 3f)
        verify(second.service).updateCalibrationAngles(1f, 2f, 3f)
        verify(first.service, never()).updateCalibrationAngles(anyFloat(), anyFloat(), anyFloat())
    }

    @Test
    fun `an unexpected service disconnect drops the service without unbinding`() = runTest {
        val bound = FakeBoundService()
        startStreamingAndBind(bound)
        val connection = capturedConnection()

        connection.onServiceDisconnected(component)
        bound.messagesSent.value = 42
        viewModel.calibration.updateCalibrationAngles(1f, 2f, 3f)

        assertEquals(0, viewModel.uiState.value.messagesSent)
        verify(bound.service, never()).updateCalibrationAngles(anyFloat(), anyFloat(), anyFloat())
        // The system already dropped the binding; unbinding again would throw.
        viewModelStore.clear()
        verify(context, never()).unbindService(any(ServiceConnection::class.java))
    }

    // --- stopStreaming ---

    @Test
    fun `stopStreaming shows the stream as stopped before the service reports it`() = runTest {
        val bound = FakeBoundService()
        startStreamingAndBind(bound)
        val connection = capturedConnection()
        bound.isStreaming.value = true
        assertTrue(viewModel.uiState.value.isStreaming)

        viewModel.stopStreaming()

        // The service still says streaming; the UI must not wait for (or be reverted by) it.
        assertFalse(viewModel.uiState.value.isStreaming)
        bound.isStreaming.value = false
        bound.isStreaming.value = true
        assertFalse(viewModel.uiState.value.isStreaming)
        verifyCalled(context) { startService(any(Intent::class.java)) }
        verify(context).unbindService(connection)
    }

    @Test
    fun `stopStreaming keeps the binding while a recording is running`() = runTest {
        val bound = FakeBoundService()
        startStreamingAndBind(bound)
        `when`(recordingSession.isRecording).thenReturn(true)

        viewModel.stopStreaming()

        assertFalse(viewModel.uiState.value.isStreaming)
        verify(context, never()).unbindService(any(ServiceConnection::class.java))
        // The binding is the only route for calibration changes to the recording's service.
        viewModel.calibration.updateCalibrationAngles(1f, 2f, 3f)
        verify(bound.service).updateCalibrationAngles(1f, 2f, 3f)
    }

    @Test
    fun `stopStreaming restarts the foreground sensors after the grace delay`() = runTest {
        viewModel.initializeSettings()
        viewModel.onAppForeground()
        startStreamingAndBind(FakeBoundService())
        clearInvocations(sensorService, locationService)

        viewModel.stopStreaming()
        advanceTimeBy(SENSOR_RESTART_DELAY_MS - 1)
        runCurrent()
        verify(sensorService, never()).startSensorUpdates(anyInt(), anyBoolean(), anyBoolean())

        advanceTimeBy(1)
        runCurrent()

        verify(sensorService).startSensorUpdates(250, needsHeading = true, needsPressure = true)
        verify(locationService).startLocationUpdates(context, 1000L)
    }

    @Test
    fun `stopStreaming in the background leaves the sensors off`() = runTest {
        viewModel.onAppForeground()
        viewModel.onAppBackground()
        startStreamingAndBind(FakeBoundService())
        clearInvocations(sensorService, locationService)

        viewModel.stopStreaming()
        advanceUntilIdle()

        verify(sensorService, never()).startSensorUpdates(anyInt(), anyBoolean(), anyBoolean())
    }

    // --- App foreground lifecycle ---

    @Test
    fun `backgrounding while idle stops sensors and GNSS`() = runTest {
        viewModel.onAppForeground()

        viewModel.onAppBackground()

        verify(sensorService).stopSensorUpdates()
        verify(locationService).stopLocationUpdates()
    }

    @Test
    fun `backgrounding while streaming leaves sensors and GNSS running`() = runTest {
        val bound = FakeBoundService()
        startStreamingAndBind(bound)
        bound.isStreaming.value = true
        viewModel.onAppForeground()

        viewModel.onAppBackground()

        verify(sensorService, never()).stopSensorUpdates()
        verify(locationService, never()).stopLocationUpdates()
    }

    @Test
    fun `backgrounding just after a recording starts leaves sensors and GNSS running`() =
        runTest {
            viewModel.onAppForeground()
            // The session already records, but its status has not reached the UI state yet.
            `when`(recordingSession.isRecording).thenReturn(true)
            assertFalse(viewModel.uiState.value.recording.isRecording)

            viewModel.onAppBackground()

            verify(sensorService, never()).stopSensorUpdates()
            verify(locationService, never()).stopLocationUpdates()
        }

    // --- initializeSettings auto-login ---

    @Test
    fun `initializeSettings logs in with stored credentials only the first time`() = runTest {
        stubStoredSettings(serverUrl = SERVER_URL, username = USERNAME, password = PASSWORD)

        viewModel.initializeSettings()
        viewModel.initializeSettings()

        verify(authenticationService, times(1)).login(SERVER_URL, USERNAME, PASSWORD)
        assertEquals(USERNAME, viewModel.uiState.value.username)
    }

    @Test
    fun `initializeSettings does not log in without a stored password`() = runTest {
        stubStoredSettings(serverUrl = SERVER_URL, username = USERNAME, password = "")

        viewModel.initializeSettings()

        verify(authenticationService, never()).login(anyString(), anyString(), anyString())
    }

    @Test
    fun `initializeSettings does not log in without a stored server URL`() = runTest {
        stubStoredSettings(serverUrl = "", username = USERNAME, password = PASSWORD)

        viewModel.initializeSettings()

        verify(authenticationService, never()).login(anyString(), anyString(), anyString())
    }

    @Test
    fun `initializeSettings does not log in when credentials appear after the first call`() =
        runTest {
            viewModel.initializeSettings()
            stubStoredSettings(serverUrl = SERVER_URL, username = USERNAME, password = PASSWORD)

            viewModel.initializeSettings()

            verify(authenticationService, never()).login(anyString(), anyString(), anyString())
        }

    // --- onCleared ---

    @Test
    fun `clearing the view model unbinds the service and stops the foreground sensors`() =
        runTest {
            startStreamingAndBind(FakeBoundService())
            val connection = capturedConnection()
            viewModel.onAppForeground()

            viewModelStore.clear()

            verify(context).unbindService(connection)
            verify(sensorService).stopSensorUpdates()
            verify(locationService).stopLocationUpdates()
        }

    @Test
    fun `clearing an unbound view model does not unbind`() = runTest {
        viewModelStore.clear()

        verify(context, never()).unbindService(any(ServiceConnection::class.java))
        verify(sensorService).stopSensorUpdates()
    }
}
