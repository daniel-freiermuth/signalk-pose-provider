package com.signalk.companion.ui.main

import android.Manifest
import android.hardware.SensorManager
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.signalk.companion.data.model.LocationData
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.replay.RecordingSession
import com.signalk.companion.service.AttitudeEngine
import com.signalk.companion.util.DeviceCalibration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val BYTES_PER_MEGABYTE = 1_000_000.0
private const val PASCALS_PER_HECTOPASCAL = 100f
private const val KELVIN_AT_ZERO_CELSIUS = 273.15
private const val PERCENT_PER_RATIO = 100f
private const val FULL_TURN_DEG = 360f

// Screen twist and heading offset are signed; tilt runs from face-up to face-down.
private val SIGNED_ANGLE_RANGE_DEG = -180f..180f
private val TILT_RANGE_DEG = 0f..180f
private val CARDINAL_MARKER_ANGLES_DEG = listOf(0.0, 90.0, 180.0, 270.0)

private val LOCATION_INTERVAL_OPTIONS: List<Pair<Long, String>> = listOf(
    500L to "500 ms",
    1000L to "1 s",
    2000L to "2 s",
    5000L to "5 s",
)

private val SENSOR_INTERVAL_OPTIONS: List<Pair<Long, String>> = listOf(
    100L to "100 ms",
    250L to "250 ms",
    500L to "500 ms",
    1000L to "1 s",
)

private val SENSOR_DISPLAY_NAMES = mapOf(
    "magnetometer" to "Magnetometer (Compass)",
    "accelerometer" to "Accelerometer (Tilt)",
    "gyroscope" to "Gyroscope (Rotation)",
    "pressure" to "Barometric Pressure",
    "temperature" to "Ambient Temperature",
    "humidity" to "Relative Humidity"
)

/** Which data types are streamed, and how often. */
private data class TransmissionOptions(
    val sendLocation: Boolean,
    val sendHeading: Boolean,
    val sendPressure: Boolean,
    val locationIntervalMs: Long,
    val sensorIntervalMs: Long
)

private data class TransmissionActions(
    val onSendLocationChange: (Boolean) -> Unit,
    val onSendHeadingChange: (Boolean) -> Unit,
    val onSendPressureChange: (Boolean) -> Unit,
    val onLocationIntervalChange: (Long) -> Unit,
    val onSensorIntervalChange: (Long) -> Unit
)

/** Device-to-vehicle mounting angles (α, β, γ) in degrees. */
private data class MountAngles(
    val alphaDeg: Float,
    val betaDeg: Float,
    val gammaDeg: Float
)

private data class AutoCalibrationActions(
    val onCalibrateAll: () -> Unit,
    val onCalibrateAzimuth: () -> Unit,
    val onCalibrateTilt: () -> Unit
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Request permissions
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    AppVisibilityEffect(viewModel)

    // Request permissions on first composition
    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    Scaffold(
        topBar = {
            MainTopBar(
                isAuthenticated = uiState.isAuthenticated,
                onNavigateToSettings = onNavigateToSettings
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ControlSection(
                uiState = uiState,
                viewModel = viewModel,
                permissionsGranted = permissionsState.allPermissionsGranted
            )
            StatusSection(
                uiState = uiState,
                viewModel = viewModel,
                permissionsGranted = permissionsState.allPermissionsGranted
            )
        }
    }
}

/** Reloads settings and manages the sensor lifecycle based on app visibility. */
@Composable
private fun AppVisibilityEffect(viewModel: MainViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.initializeSettings()
                    viewModel.onAppForeground()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.onAppBackground()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(isAuthenticated: Boolean, onNavigateToSettings: () -> Unit) {
    TopAppBar(
        title = { Text("SignalK Pose Provider") },
        actions = {
            // Authentication status indicator
            if (isAuthenticated) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // Settings button
            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings"
                )
            }
        }
    )
}

/** The cards the user acts on: what to send, start/stop, mounting calibration, errors. */
@Composable
private fun ControlSection(
    uiState: MainUiState,
    viewModel: MainViewModel,
    permissionsGranted: Boolean
) {
    DataTransmissionCard(
        settings = TransmissionOptions(
            sendLocation = uiState.sendLocation,
            sendHeading = uiState.sendHeading,
            sendPressure = uiState.sendPressure,
            locationIntervalMs = uiState.locationIntervalMs,
            sensorIntervalMs = uiState.sensorIntervalMs
        ),
        actions = TransmissionActions(
            onSendLocationChange = viewModel.transmissionOptions::updateSendLocation,
            onSendHeadingChange = viewModel.transmissionOptions::updateSendHeading,
            onSendPressureChange = viewModel.transmissionOptions::updateSendPressure,
            onLocationIntervalChange = viewModel.transmissionOptions::updateLocationIntervalMs,
            onSensorIntervalChange = viewModel.transmissionOptions::updateSensorIntervalMs
        )
    )

    ControlCard(
        isStreaming = uiState.isStreaming,
        onStartStop = {
            if (uiState.isStreaming) {
                viewModel.stopStreaming()
            } else if (permissionsGranted) {
                viewModel.startStreaming()
            }
        },
        permissionsGranted = permissionsGranted
    )

    MarineConfigCard(
        angles = MountAngles(
            alphaDeg = uiState.calibrationAlphaDeg,
            betaDeg = uiState.calibrationBetaDeg,
            gammaDeg = uiState.calibrationGammaDeg
        ),
        onAnglesChange = viewModel.calibration::updateCalibrationAngles,
        actions = AutoCalibrationActions(
            onCalibrateAll = viewModel.calibration::calibrateAll,
            onCalibrateAzimuth = viewModel.calibration::calibrateAzimuth,
            onCalibrateTilt = viewModel.calibration::calibrateTilt
        ),
        hasGps = hasCalibrationGps(uiState.locationData)
    )

    uiState.error?.let { error ->
        ErrorCard(
            error = error,
            onDismiss = viewModel::clearError
        )
    }

    val accuracy = uiState.sensorData?.magnetometerAccuracy
    if (accuracy != null && accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
        CompassAccuracyBanner(accuracy)
    }
}

/** The read-only cards: connection, sensors, recording and what was last sent. */
@Composable
private fun StatusSection(
    uiState: MainUiState,
    viewModel: MainViewModel,
    permissionsGranted: Boolean
) {
    ConnectionStatusCard(
        isConnected = uiState.isConnected,
        isStreaming = uiState.isStreaming,
        isAuthenticated = uiState.isAuthenticated,
        username = uiState.username
    )

    SensorAvailabilityCard(viewModel = viewModel)

    SensorDataCard(
        locationData = uiState.locationData,
        sensorData = uiState.sensorData
    )

    // Raw Recording Card (M1)
    RecordingCard(
        recording = uiState.recording,
        attitude = uiState.attitude,
        permissionsGranted = permissionsGranted,
        onToggle = { viewModel.toggleRecording() }
    )

    if (uiState.isStreaming) {
        LiveTransmissionCard(
            lastSentMessage = uiState.lastSentMessage,
            messagesSent = uiState.messagesSent,
            lastTransmissionTime = uiState.lastTransmissionTime
        )
    }
}

/** Azimuth calibration needs a GPS course, which is only meaningful above a minimum speed. */
private fun hasCalibrationGps(location: LocationData?): Boolean =
    location?.bearing != null &&
        (location.speed ?: 0f) >= DeviceCalibration.MIN_CALIBRATION_SPEED_MPS

@Composable
private fun CompassAccuracyBanner(accuracy: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚠",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    "Compass unreliable — wave your phone in a figure-8 to calibrate"
                } else {
                    "Compass accuracy low — wave your phone in a figure-8 to calibrate"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    isConnected: Boolean,
    isStreaming: Boolean,
    isAuthenticated: Boolean,
    username: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Server connection status
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val connectionColor = when {
                    isConnected -> MaterialTheme.colorScheme.primary
                    isStreaming -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = "●",
                    color = connectionColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = when {
                        isConnected -> "Connected"
                        isStreaming -> "Connecting…"
                        else -> "Disconnected"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = connectionColor
                )
            }
            // Auth status
            Text(
                text = if (isAuthenticated && username != null) {
                    "✓ $username"
                } else {
                    "Not authenticated"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isAuthenticated) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * Raw sensor recording, and the M1 filter's own pose beside it (M1).
 *
 * The pose here is **not** what the app publishes — `SensorService` still owns that. It is
 * shown next to the legacy value on purpose: an untuned filter's number is only meaningful
 * as a comparison, and having both on one screen is what makes a disagreement visible while
 * you are still on the water and can note what the boat was doing.
 */
@Composable
private fun RecordingCard(
    recording: RecordingSession.Status,
    attitude: AttitudeEngine.State?,
    permissionsGranted: Boolean,
    onToggle: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CardTitle("Raw Recording")

            Text(
                text = "Records uncalibrated sensors at 200 Hz for offline replay. " +
                    "About 35 kB/s — roughly 130 MB per hour.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StartStopButton(
                isRunning = recording.isRecording,
                enabled = permissionsGranted || recording.isRecording,
                subject = "Recording",
                onClick = onToggle
            )

            RecordingStatusDetails(recording)

            if (attitude != null) {
                HorizontalDivider()
                FilterComparison(attitude)
            }
        }
    }
}

@Composable
private fun RecordingStatusDetails(recording: RecordingSession.Status) {
    recording.fileName?.let { name ->
        SensorDataRow("File", name)
        SensorDataRow("Records", recording.recordCount.toString())
        SensorDataRow(
            "Size",
            formatLocalized("%.1f MB", recording.bytesWritten / BYTES_PER_MEGABYTE)
        )
    }

    if (recording.isTruncated) {
        ErrorText(
            "Recording stopped at the size limit — the sail is complete up to that point."
        )
    }

    recording.error?.let { message ->
        ErrorText("Recording failed: $message")
    }
}

@Composable
private fun FilterComparison(attitude: AttitudeEngine.State) {
    Text(
        text = "Filter (comparison only — not published)",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    SensorDataRow("Heading", degreesText(attitude.headingRad))
    SensorDataRow("Heel", degreesText(attitude.rollRad))
    SensorDataRow("Pitch", degreesText(attitude.pitchRad))
    SensorDataRow("Rate of turn", degreesText(attitude.rateOfTurnRadS, "%.1f°/s"))
    attitude.referenceHeadingRad?.let {
        SensorDataRow("Android heading", degreesText(it))
    }
    // The gate states are the beginning of M5's quality publishing: an instrument
    // that says why it is coasting is worth more than one that goes quiet.
    SensorDataRow(
        "Corrections",
        listOfNotNull(
            if (attitude.accelerometerAccepted) "accel" else null,
            if (attitude.magnetometerAccepted) "mag" else null,
            if (attitude.biasEstimatorRunning) "bias" else null
        ).joinToString(", ").ifEmpty { "coasting on gyro" }
    )
    SensorDataRow("Gyro bias", degreesText(attitude.gyroBiasMagnitude, "%.2f°/s"))
}

@Composable
private fun ControlCard(
    isStreaming: Boolean,
    onStartStop: () -> Unit,
    permissionsGranted: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CardTitle("Data Streaming")

            if (!permissionsGranted) {
                Text(
                    text = "Location permissions required",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            StartStopButton(
                isRunning = isStreaming,
                enabled = permissionsGranted || isStreaming,
                subject = "Streaming",
                onClick = onStartStop
            )
        }
    }
}

/** Full-width "Start/Stop [subject]" button with a matching play/close icon. */
@Composable
private fun StartStopButton(
    isRunning: Boolean,
    enabled: Boolean,
    subject: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(if (isRunning) "Stop $subject" else "Start $subject")
    }
}

@Composable
private fun SensorDataCard(
    locationData: LocationData?,
    sensorData: SensorData?
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CardTitle("Sensor Data")

            if (locationData != null) {
                GpsSection(locationData)
            } else {
                HintText("No GPS data available")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (sensorData != null) {
                DeviceSensorsSection(sensorData)
            } else {
                HintText("Device sensors initializing...")
            }
        }
    }
}

@Composable
private fun GpsSection(location: LocationData) {
    SectionTitle("GPS Navigation")
    SensorDataRow("Latitude", "${location.latitude}")
    SensorDataRow("Longitude", "${location.longitude}")
    SensorDataRow("Speed over Ground", "${formatLocalized("%.2f", location.speed)} m/s")
    SensorDataRow("GPS Bearing", "${formatLocalized("%.1f", location.bearing)}°")
    SensorDataRow("GPS Accuracy", "${formatLocalized("%.1f", location.accuracy)} m")
    SensorDataRow("Altitude", "${formatLocalized("%.1f", location.altitude)} m")
    location.satellites?.let { sats ->
        SensorDataRow("Satellites", "$sats")
    }
}

@Composable
private fun DeviceSensorsSection(sensor: SensorData) {
    val hasOrientationData = sensor.hasOrientationData()
    val hasEnvironmentalData = sensor.hasEnvironmentalData()

    if (hasOrientationData) {
        OrientationSection(sensor)
    }
    if (hasEnvironmentalData) {
        if (hasOrientationData) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        EnvironmentalSection(sensor)
    }
    if (!hasOrientationData && !hasEnvironmentalData) {
        HintText("No device sensors available or active")
    }
}

private fun SensorData.hasOrientationData(): Boolean =
    listOf(compassHeading, approxTrueHeading, roll, pitch, rateOfTurn).any { it != null }

private fun SensorData.hasEnvironmentalData(): Boolean =
    listOf(pressure, temperature, relativeHumidity).any { it != null }

@Composable
private fun OrientationSection(sensor: SensorData) {
    SectionTitle("Device Orientation")
    sensor.compassHeading?.let { heading ->
        SensorDataRow("Compass Heading", degreesText(heading))
    }
    // "approx" is load-bearing: deviation is not corrected out until M2, so
    // this can be tens of degrees off near the engine or a speaker.
    sensor.approxTrueHeading?.let { heading ->
        SensorDataRow("True Heading (approx)", degreesText(heading))
    }
    sensor.magnetometerAccuracy?.let { accuracy ->
        CompassAccuracyRow(accuracy)
    }
    sensor.roll?.let { roll ->
        SensorDataRow("Heel / roll (+stbd down)", degreesText(roll))
    }
    sensor.pitch?.let { pitch ->
        SensorDataRow("Pitch (+bow up)", degreesText(pitch))
    }
    sensor.rateOfTurn?.let { rate ->
        SensorDataRow("Rate of Turn (+stbd)", degreesText(rate, "%.2f°/s"))
    }
}

@Composable
private fun CompassAccuracyRow(accuracy: Int) {
    val (label, color) = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High" to MaterialTheme.colorScheme.primary
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
            "Medium" to MaterialTheme.colorScheme.secondary
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low" to MaterialTheme.colorScheme.error
        else -> "Unreliable" to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Compass Accuracy",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun EnvironmentalSection(sensor: SensorData) {
    SectionTitle("Environmental")
    sensor.pressure?.let { pressurePa ->
        val pressureHpa = pressurePa / PASCALS_PER_HECTOPASCAL
        SensorDataRow("Barometric Pressure", "${formatLocalized("%.2f", pressureHpa)} hPa")
    }
    sensor.temperature?.let { temperatureK ->
        val temperatureC = temperatureK - KELVIN_AT_ZERO_CELSIUS
        SensorDataRow("Temperature", "${formatLocalized("%.1f", temperatureC)}°C")
    }
    sensor.relativeHumidity?.let { humidityRatio ->
        val humidityPercent = humidityRatio * PERCENT_PER_RATIO
        SensorDataRow("Humidity", "${formatLocalized("%.1f", humidityPercent)}%")
    }
}

@Composable
private fun SensorDataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun LiveTransmissionCard(
    lastSentMessage: String?,
    messagesSent: Int,
    lastTransmissionTime: Long?
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CardTitle("Live Transmission")

            // Transmission stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SensorDataRow("Messages Sent", messagesSent.toString())
                lastTransmissionTime?.let { time ->
                    Text(
                        text = "Last: ${timeFormat.format(Date(time))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (lastSentMessage != null) {
                LastSentMessage(lastSentMessage)
            } else {
                HintText("No messages sent yet")
            }
        }
    }
}

/** The last sent JSON message, horizontally scrollable in a monospace box. */
@Composable
private fun LastSentMessage(message: String) {
    Text(
        text = "Last Message:",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(
    error: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Text(
                text = "• Check server address (hostnames like 'signalk.local' are supported)\n" +
                    "• Ensure network connectivity\n" +
                    "• Verify SignalK server is running",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun SensorAvailabilityCard(viewModel: MainViewModel) {
    val availableSensors = remember { viewModel.getAvailableSensors() }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CardTitle("Device Sensors")

            HintText("Available sensors on this device:")

            SENSOR_DISPLAY_NAMES.forEach { (key, displayName) ->
                SensorAvailabilityRow(
                    sensorName = displayName,
                    isAvailable = availableSensors[key] ?: false
                )
            }

            if (availableSensors.values.any { it }) {
                Text(
                    text = "✓ Available sensors will be included in SignalK data stream",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "⚠ No device sensors detected - only GPS data will be transmitted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SensorAvailabilityRow(sensorName: String, isAvailable: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = sensorName,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = if (isAvailable) "✓ Available" else "✗ Not Available",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isAvailable) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun MarineConfigCard(
    angles: MountAngles,
    onAnglesChange: (alphaDeg: Float, betaDeg: Float, gammaDeg: Float) -> Unit,
    actions: AutoCalibrationActions,
    hasGps: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CardTitle("⚓ Marine Configuration")

            HintText(
                "Device-to-vehicle calibration. " +
                    "Adjust mounting angles or use automatic calibration."
            )

            MountAngleDials(angles = angles, onAnglesChange = onAnglesChange)

            SectionTitle("Automatic Calibration")

            AutoCalibrationButtons(actions = actions, hasGps = hasGps)

            if (!hasGps) {
                Text(
                    text = "Needs GPS course and at least " +
                        "${DeviceCalibration.MIN_CALIBRATION_SPEED_KN} kn of speed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Procedure matters as much as the thresholds: heading comes from the compass,
            // so a bad compass produces a bad mount constant, and a marina is the worst
            // magnetic environment there is (steel piles, quay walls, wiring, other boats).
            Text(
                text = "Best results: swing the phone in a figure-of-eight BEFORE mounting " +
                    "it, then calibrate motoring straight and level, clear of the marina " +
                    "and in slack water.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Calibration dials, side by side; each one changes only its own angle. */
@Composable
private fun MountAngleDials(
    angles: MountAngles,
    onAnglesChange: (alphaDeg: Float, betaDeg: Float, gammaDeg: Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CalibrationDial(
            label = "Screen Twist (α)",
            value = angles.alphaDeg,
            range = SIGNED_ANGLE_RANGE_DEG,
            onValueChange = { v -> onAnglesChange(v, angles.betaDeg, angles.gammaDeg) },
            modifier = Modifier.weight(1f)
        )

        CalibrationDial(
            label = "Tilt (β)",
            value = angles.betaDeg,
            range = TILT_RANGE_DEG,
            onValueChange = { v -> onAnglesChange(angles.alphaDeg, v, angles.gammaDeg) },
            modifier = Modifier.weight(1f)
        )

        CalibrationDial(
            label = "Heading Offset (γ)",
            value = angles.gammaDeg,
            range = SIGNED_ANGLE_RANGE_DEG,
            onValueChange = { v -> onAnglesChange(angles.alphaDeg, angles.betaDeg, v) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AutoCalibrationButtons(actions: AutoCalibrationActions, hasGps: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = actions.onCalibrateAll,
            modifier = Modifier.weight(1f)
        ) {
            Text("Calibrate All")
        }

        Button(
            onClick = actions.onCalibrateAzimuth,
            enabled = hasGps,
            modifier = Modifier.weight(1f)
        ) {
            Text("Azimuth Only")
        }

        Button(
            onClick = actions.onCalibrateTilt,
            modifier = Modifier.weight(1f)
        ) {
            Text("Twist/Tilt Only")
        }
    }
}

@Composable
private fun CalibrationDial(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Value display
        Text(
            text = "${formatAngle(value)}°",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Extract colors outside Canvas lambda
        val outlineColor = MaterialTheme.colorScheme.outline
        val primaryColor = MaterialTheme.colorScheme.primary

        // The gesture handler outlives recompositions: each drag update changes the angles
        // and so the callback's identity. Keying pointerInput on the callback would restart
        // detectDragGestures mid-drag, so read the latest callback through state instead.
        val currentOnValueChange by rememberUpdatedState(onValueChange)

        // Circular dial — clickable and draggable to adjust
        Canvas(
            modifier = Modifier
                .size(100.dp)
                .pointerInput(range) {
                    detectDragGestures { change, _ ->
                        val angleDeg = dialAngleDeg(change.position, size)
                        val finalValue = dialValueForAngle(angleDeg, range)
                        Log.d("CalibrationDial", "$label: angle=$angleDeg° → value=$finalValue")
                        currentOnValueChange(finalValue)
                    }
                }
        ) {
            drawDial(
                value = value,
                range = range,
                outlineColor = outlineColor,
                indicatorColor = primaryColor
            )
        }
    }
}

/** Angle of [position] around the centre of a dial of [size], in [0, 360)°. */
private fun dialAngleDeg(position: Offset, size: IntSize): Float {
    val x = position.x - size.width / 2
    val y = position.y - size.height / 2
    // atan2 gives -π to π, convert to 0-360°
    val angleDeg = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
    return if (angleDeg < 0) angleDeg + FULL_TURN_DEG else angleDeg
}

/** Maps a dial angle of 0–360° linearly onto [range]. */
private fun dialValueForAngle(angleDeg: Float, range: ClosedFloatingPointRange<Float>): Float {
    val normalized = angleDeg / FULL_TURN_DEG
    val newValue = range.start + normalized * (range.endInclusive - range.start)
    return newValue.coerceIn(range)
}

private fun DrawScope.drawDial(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    outlineColor: Color,
    indicatorColor: Color
) {
    val center = Offset(size.width / 2, size.height / 2)
    val circleRadius = size.minDimension / 2 - 4.dp.toPx()

    // Draw outer circle
    drawCircle(
        color = outlineColor,
        radius = circleRadius,
        center = center,
        style = Stroke(width = 2.dp.toPx())
    )

    // Draw angle indicator
    val normalizedValue = (value - range.start) / (range.endInclusive - range.start)
    val angleRad = Math.toRadians(normalizedValue * FULL_TURN_DEG.toDouble())
    val indicatorRadius = circleRadius - 10.dp.toPx()
    drawLine(
        color = indicatorColor,
        start = center,
        end = Offset(
            center.x + (indicatorRadius * cos(angleRad)).toFloat(),
            center.y + (indicatorRadius * sin(angleRad)).toFloat()
        ),
        strokeWidth = 3.dp.toPx()
    )

    // Draw cardinal point markers
    CARDINAL_MARKER_ANGLES_DEG.forEach { angle ->
        val rad = Math.toRadians(angle)
        drawCircle(
            color = outlineColor,
            radius = 2.5.dp.toPx(),
            center = Offset(
                center.x + (circleRadius * cos(rad)).toFloat(),
                center.y + (circleRadius * sin(rad)).toFloat()
            )
        )
    }
}

private fun formatAngle(degrees: Float): String {
    return if (degrees == degrees.toLong().toFloat()) {
        degrees.toLong().toString()
    } else {
        formatLocalized("%.1f", degrees)
    }
}

@Composable
private fun DataTransmissionCard(settings: TransmissionOptions, actions: TransmissionActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CardTitle("Data Transmission")

            HintText("Choose which data types to transmit to SignalK server:")

            TransmissionOptionRow(
                title = "Location Data",
                description = "GPS position, course, and speed",
                checked = settings.sendLocation,
                onCheckedChange = actions.onSendLocationChange
            ) {
                IntervalDropdown(
                    options = LOCATION_INTERVAL_OPTIONS,
                    selected = settings.locationIntervalMs,
                    onSelected = actions.onLocationIntervalChange,
                    enabled = settings.sendLocation
                )
            }

            TransmissionOptionRow(
                title = "Heading Data",
                description = "Magnetic and true compass heading",
                checked = settings.sendHeading,
                onCheckedChange = actions.onSendHeadingChange
            ) {
                IntervalDropdown(
                    options = SENSOR_INTERVAL_OPTIONS,
                    selected = settings.sensorIntervalMs,
                    onSelected = actions.onSensorIntervalChange,
                    enabled = settings.sendHeading
                )
            }

            TransmissionOptionRow(
                title = "Atmospheric Pressure",
                description = "Barometric pressure sensor data",
                checked = settings.sendPressure,
                onCheckedChange = actions.onSendPressureChange
            )
        }
    }
}

/** Checkbox, title and description, with an optional control (e.g. an interval) at the end. */
@Composable
private fun TransmissionOptionRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalDropdown(
    options: List<Pair<Long, String>>,
    selected: Long,
    onSelected: (Long) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.find { it.first == selected }?.second ?: "$selected ms"

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .width(100.dp)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}

/** [radians] shown in degrees with [pattern], e.g. `"%.1f°"`. */
private fun degreesText(radians: Float, pattern: String = "%.1f°"): String =
    formatLocalized(pattern, Math.toDegrees(radians.toDouble()))

/** Numbers on this screen are read by the user, so they follow the device locale. */
private fun formatLocalized(pattern: String, vararg args: Any?): String =
    String.format(Locale.getDefault(), pattern, *args)
