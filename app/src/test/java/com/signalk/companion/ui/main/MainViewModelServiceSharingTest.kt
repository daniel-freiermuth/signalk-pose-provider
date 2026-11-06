package com.signalk.companion.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import com.signalk.companion.data.model.AuthState
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.replay.RecordingSession
import com.signalk.companion.service.AttitudeEngine
import com.signalk.companion.service.AuthenticationService
import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SensorService
import com.signalk.companion.service.SignalKStreamingService
import com.signalk.companion.service.SignalKTransmitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * SensorService and LocationService are app-wide singletons that the main screen and
 * SignalKStreamingService both drive. Whatever the screen does with them when it goes away
 * or into the background must not cut off a streaming session the service is still running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelServiceSharingTest {

    /**
     * Stands in for the framework's bookkeeping of SignalKStreamingService: which connections
     * are bound with which flags, whether the service exists, and delivering
     * onServiceConnected to bound connections once it does (asynchronously, as on a device,
     * so tests decide when the main looper "runs" via [dispatchConnections]).
     */
    private class FakeServiceHost(private val binder: SignalKStreamingService.LocalBinder) {
        var running = false
            private set
        val bindings = mutableListOf<Pair<ServiceConnection, Int>>()
        private val connected = mutableSetOf<ServiceConnection>()
        private val name = mock(ComponentName::class.java)

        fun bind(connection: ServiceConnection, flags: Int): Boolean {
            bindings += connection to flags
            if (flags and Context.BIND_AUTO_CREATE != 0) running = true
            return true
        }

        fun unbind(connection: ServiceConnection) {
            require(bindings.any { it.first == connection }) { "Service not registered" }
            bindings.removeAll { it.first == connection }
            connected -= connection
        }

        fun start() {
            running = true
        }

        fun dispatchConnections() {
            if (!running) return
            bindings.map { it.first }.distinct().filter { it !in connected }.forEach {
                connected += it
                it.onServiceConnected(name, binder)
            }
        }

        /** The service destroyed (stopped from its notification, say); clients are told. */
        fun destroy() {
            running = false
            connected.forEach { it.onServiceDisconnected(name) }
            connected.clear()
        }
    }

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var locationService: LocationService
    private lateinit var sensorService: SensorService
    private lateinit var signalKTransmitter: SignalKTransmitter
    private lateinit var authenticationService: AuthenticationService
    private lateinit var recordingSession: RecordingSession
    private lateinit var attitudeEngine: AttitudeEngine
    private lateinit var serviceStreaming: MutableStateFlow<Boolean>
    private lateinit var host: FakeServiceHost

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.getString(eq("server_url"), anyString())).thenReturn("http://10.0.0.1:3000")
        context = mock(Context::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)

        locationService = mock(LocationService::class.java)
        `when`(locationService.locationUpdates).thenReturn(MutableStateFlow(null))
        sensorService = mock(SensorService::class.java)
        `when`(sensorService.sensorData).thenReturn(MutableStateFlow(SensorData()))
        signalKTransmitter = mock(SignalKTransmitter::class.java)
        `when`(signalKTransmitter.connectionStatus).thenReturn(MutableStateFlow(false))
        `when`(signalKTransmitter.lastSentMessage).thenReturn(MutableStateFlow(null))
        `when`(signalKTransmitter.authenticationError).thenReturn(MutableStateFlow(null))
        authenticationService = mock(AuthenticationService::class.java)
        `when`(authenticationService.authState).thenReturn(MutableStateFlow(AuthState()))
        recordingSession = mock(RecordingSession::class.java)
        `when`(recordingSession.status).thenReturn(MutableStateFlow(RecordingSession.Status()))
        attitudeEngine = mock(AttitudeEngine::class.java)
        `when`(attitudeEngine.state).thenReturn(MutableStateFlow(null))

        serviceStreaming = MutableStateFlow(false)
        val service = mock(SignalKStreamingService::class.java)
        `when`(service.isStreaming).thenReturn(serviceStreaming)
        `when`(service.messagesSent).thenReturn(MutableStateFlow(0))
        `when`(service.lastTransmissionTime).thenReturn(MutableStateFlow(null))
        `when`(service.error).thenReturn(MutableStateFlow(null))
        val binder = mock(SignalKStreamingService.LocalBinder::class.java)
        `when`(binder.getService()).thenReturn(service)

        host = FakeServiceHost(binder)
        `when`(context.bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt()))
            .thenAnswer { host.bind(it.getArgument(1), it.getArgument(2)) }
        `when`(context.unbindService(any(ServiceConnection::class.java)))
            .thenAnswer { host.unbind(it.getArgument(0)) }
        val component = mock(ComponentName::class.java)
        `when`(context.startForegroundService(any(Intent::class.java))).thenAnswer {
            host.start()
            component
        }
        `when`(context.startService(any(Intent::class.java))).thenReturn(component)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = MainViewModel(
        context,
        locationService,
        sensorService,
        signalKTransmitter,
        authenticationService,
        recordingSession,
        attitudeEngine
    )

    /** What the activity finishing does to its ViewModels (swipe from Recents, Back). */
    private fun clear(viewModel: MainViewModel) {
        ViewModelStore().apply { put("main", viewModel) }.clear()
    }

    /** The service streaming in a process that outlived the screen that started it. */
    private fun serviceAlreadyStreaming() {
        host.start()
        serviceStreaming.value = true
    }

    @Test
    fun `closing the screen while streaming leaves the service's sensors and GNSS running`() {
        val viewModel = newViewModel()
        viewModel.initializeSettings()
        viewModel.onAppForeground()
        viewModel.startStreaming()
        serviceStreaming.value = true
        host.dispatchConnections()

        clear(viewModel)

        verify(sensorService, never()).stopSensorUpdates()
        verify(locationService, never()).stopLocationUpdates()
    }

    @Test
    fun `a screen reopened while streaming does not stop the service's sources in the background`() {
        serviceAlreadyStreaming()

        val viewModel = newViewModel()
        host.dispatchConnections()
        viewModel.initializeSettings()
        viewModel.onAppForeground()
        viewModel.onAppBackground()

        verify(sensorService, never()).stopSensorUpdates()
        verify(locationService, never()).stopLocationUpdates()
    }

    @Test
    fun `a screen reopened while streaming shows the session as running`() {
        serviceAlreadyStreaming()

        val viewModel = newViewModel()
        host.dispatchConnections()

        assertEquals(true, viewModel.uiState.value.isStreaming)
    }

    @Test
    fun `a reopened screen sees the service stop even before its last state arrives`() {
        serviceAlreadyStreaming()
        val viewModel = newViewModel()
        host.dispatchConnections()
        viewModel.onAppForeground()

        // The service is gone; whether its IDLE reached the collector first is a race.
        host.destroy()
        viewModel.onAppBackground()

        assertFalse(viewModel.uiState.value.isStreaming)
        verify(sensorService).stopSensorUpdates()
    }

    @Test
    fun `without a streaming service the screen releases the sensors when backgrounded`() {
        val viewModel = newViewModel()
        host.dispatchConnections()
        viewModel.initializeSettings()
        viewModel.onAppForeground()
        viewModel.onAppBackground()

        verify(sensorService).stopSensorUpdates()
        verify(locationService).stopLocationUpdates()
        assertFalse(host.running, "Opening the screen must not create the streaming service")
    }

    @Test
    fun `without a streaming service closing the screen releases the sensors and its binding`() {
        val viewModel = newViewModel()
        host.dispatchConnections()
        viewModel.onAppForeground()

        clear(viewModel)

        verify(sensorService).stopSensorUpdates()
        verify(locationService).stopLocationUpdates()
        assertEquals(emptyList<Pair<ServiceConnection, Int>>(), host.bindings)
    }
}
