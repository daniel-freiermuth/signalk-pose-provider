package com.signalk.companion.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.signalk.companion.util.DeviceCalibration
import android.hardware.SensorManager
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Request permissions
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )
    
    // Reload settings and manage sensor lifecycle based on app visibility
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
    
    // Request permissions on first composition
    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SignalK Pose Provider") },
                actions = {
                    // Authentication status indicator
                    if (uiState.isAuthenticated) {
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Data Transmission Options Card
            DataTransmissionCard(
                sendLocation = uiState.sendLocation,
                sendHeading = uiState.sendHeading,
                sendPressure = uiState.sendPressure,
                locationIntervalMs = uiState.locationIntervalMs,
                sensorIntervalMs = uiState.sensorIntervalMs,
                onSendLocationChange = viewModel::updateSendLocation,
                onSendHeadingChange = viewModel::updateSendHeading,
                onSendPressureChange = viewModel::updateSendPressure,
                onLocationIntervalChange = viewModel::updateLocationIntervalMs,
                onSensorIntervalChange = viewModel::updateSensorIntervalMs
            )
            
            // Control Card
            ControlCard(
                isStreaming = uiState.isStreaming,
                onStartStop = {
                    if (uiState.isStreaming) {
                        viewModel.stopStreaming()
                    } else {
                        if (permissionsState.allPermissionsGranted) {
                            viewModel.startStreaming()
                        }
                    }
                },
                permissionsGranted = permissionsState.allPermissionsGranted
            )
            
            // Marine Configuration Card
            MarineConfigCard(
                calibrationAlphaDeg = uiState.calibrationAlphaDeg,
                calibrationBetaDeg = uiState.calibrationBetaDeg,
                calibrationGammaDeg = uiState.calibrationGammaDeg,
                onCalibrationAnglesChange = viewModel::updateCalibrationAngles,
                onCalibrateAll = viewModel::calibrateAll,
                onCalibrateAzimuth = viewModel::calibrateAzimuth,
                onCalibrateTilt = viewModel::calibrateTilt,
                hasGps = uiState.locationData?.bearing != null &&
                    (uiState.locationData?.speed ?: 0f) >= DeviceCalibration.MIN_CALIBRATION_SPEED_MPS
            )
            
            // Error Card
            uiState.error?.let { error ->
                ErrorCard(
                    error = error,
                    onDismiss = viewModel::clearError
                )
            }

            // Compass accuracy warning banner
            val accuracy = uiState.sensorData?.magnetometerAccuracy
            if (accuracy != null && accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
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
                            text = if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE)
                                "Compass unreliable — wave your phone in a figure-8 to calibrate"
                            else
                                "Compass accuracy low — wave your phone in a figure-8 to calibrate",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            // Connection & Authentication Status Card
            ConnectionStatusCard(
                isConnected = uiState.isConnected,
                isStreaming = uiState.isStreaming,
                isAuthenticated = uiState.isAuthenticated,
                username = uiState.username
            )
            
            // Sensor Availability Card
            SensorAvailabilityCard(viewModel = viewModel)
            
            // Sensor Data Card
            SensorDataCard(
                locationData = uiState.locationData,
                sensorData = uiState.sensorData
            )
            
            // Live Transmission Card
            if (uiState.isStreaming) {
                LiveTransmissionCard(
                    lastSentMessage = uiState.lastSentMessage,
                    messagesSent = uiState.messagesSent,
                    lastTransmissionTime = uiState.lastTransmissionTime
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
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
                text = if (isAuthenticated && username != null) "✓ $username" else "Not authenticated",
                style = MaterialTheme.typography.bodySmall,
                color = if (isAuthenticated)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ControlCard(
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
            Text(
                text = "Data Streaming",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            if (!permissionsGranted) {
                Text(
                    text = "Location permissions required",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            Button(
                onClick = onStartStop,
                enabled = permissionsGranted || isStreaming,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (isStreaming) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isStreaming) "Stop Streaming" else "Start Streaming")
            }
        }
    }
}

@Composable
fun SensorDataCard(
    locationData: com.signalk.companion.data.model.LocationData?,
    sensorData: com.signalk.companion.data.model.SensorData?
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Sensor Data",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            // Location/GPS Data Section
            locationData?.let { location ->
                Text(
                    text = "GPS Navigation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                SensorDataRow("Latitude", "${location.latitude}")
                SensorDataRow("Longitude", "${location.longitude}")
                SensorDataRow("Speed over Ground", "${String.format("%.2f", location.speed)} m/s")
                SensorDataRow("GPS Bearing", "${String.format("%.1f", location.bearing)}°")
                SensorDataRow("GPS Accuracy", "${String.format("%.1f", location.accuracy)} m")
                
                location.altitude.let { alt ->
                    SensorDataRow("Altitude", "${String.format("%.1f", alt)} m")
                }
                
                location.satellites?.let { sats ->
                    SensorDataRow("Satellites", "$sats")
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            } ?: run {
                Text(
                    text = "No GPS data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            
            // Device Sensors Section
            sensorData?.let { sensor ->
                var hasOrientationData = false
                var hasEnvironmentalData = false
                
                // Navigation/Orientation Data
                if (sensor.compassHeading != null || sensor.approxTrueHeading != null ||
                    sensor.roll != null || sensor.pitch != null ||
                    sensor.rateOfTurn != null) {
                    hasOrientationData = true
                    Text(
                        text = "Device Orientation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    sensor.compassHeading?.let { heading ->
                        SensorDataRow("Compass Heading", "${String.format("%.1f", Math.toDegrees(heading.toDouble()))}°")
                    }
                    // "approx" is load-bearing: deviation is not corrected out until M2, so
                    // this can be tens of degrees off near the engine or a speaker.
                    sensor.approxTrueHeading?.let { heading ->
                        SensorDataRow("True Heading (approx)", "${String.format("%.1f", Math.toDegrees(heading.toDouble()))}°")
                    }
                    sensor.magnetometerAccuracy?.let { acc ->
                        val (label, color) = when (acc) {
                            SensorManager.SENSOR_STATUS_ACCURACY_HIGH   -> "High" to MaterialTheme.colorScheme.primary
                            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium" to MaterialTheme.colorScheme.secondary
                            SensorManager.SENSOR_STATUS_ACCURACY_LOW    -> "Low" to MaterialTheme.colorScheme.error
                            else                                          -> "Unreliable" to MaterialTheme.colorScheme.error
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
                    sensor.roll?.let { roll ->
                        SensorDataRow("Heel / roll (+stbd down)", "${String.format("%.1f", Math.toDegrees(roll.toDouble()))}°")
                    }
                    sensor.pitch?.let { pitch ->
                        SensorDataRow("Pitch (+bow up)", "${String.format("%.1f", Math.toDegrees(pitch.toDouble()))}°")
                    }
                    sensor.rateOfTurn?.let { rate ->
                        SensorDataRow("Rate of Turn (+stbd)", "${String.format("%.2f", Math.toDegrees(rate.toDouble()))}°/s")
                    }
                }
                
                // Environmental Data
                if (sensor.pressure != null || sensor.temperature != null || 
                    sensor.relativeHumidity != null) {
                    if (hasOrientationData) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    hasEnvironmentalData = true
                    Text(
                        text = "Environmental",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    sensor.pressure?.let { pressure ->
                        SensorDataRow("Barometric Pressure", "${String.format("%.2f", pressure / 100)} hPa")
                    }
                    sensor.temperature?.let { temp ->
                        SensorDataRow("Temperature", "${String.format("%.1f", temp - 273.15)}°C")
                    }
                    sensor.relativeHumidity?.let { humidity ->
                        SensorDataRow("Humidity", "${String.format("%.1f", humidity * 100)}%")
                    }
                }
                
                if (!hasOrientationData && !hasEnvironmentalData) {
                    Text(
                        text = "No device sensors available or active",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } ?: run {
                Text(
                    text = "Device sensors initializing...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SensorDataRow(label: String, value: String) {
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
fun LiveTransmissionCard(
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
            Text(
                text = "Live Transmission",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
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
            
            // Last sent JSON message
            lastSentMessage?.let { message ->
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
            } ?: run {
                Text(
                    text = "No messages sent yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ErrorCard(
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
                text = "• Check server address (hostnames like 'signalk.local' are supported)\n• Ensure network connectivity\n• Verify SignalK server is running",
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
fun SensorAvailabilityCard(viewModel: MainViewModel) {
    val availableSensors = remember { viewModel.getAvailableSensors() }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Device Sensors",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Available sensors on this device:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Create rows for sensor availability
            val sensorDisplayNames = mapOf(
                "magnetometer" to "Magnetometer (Compass)",
                "accelerometer" to "Accelerometer (Tilt)",
                "gyroscope" to "Gyroscope (Rotation)",
                "pressure" to "Barometric Pressure",
                "temperature" to "Ambient Temperature",
                "humidity" to "Relative Humidity"
            )
            
            sensorDisplayNames.forEach { (key, displayName) ->
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
fun SensorAvailabilityRow(sensorName: String, isAvailable: Boolean) {
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
fun MarineConfigCard(
    calibrationAlphaDeg: Float,
    calibrationBetaDeg: Float,
    calibrationGammaDeg: Float,
    onCalibrationAnglesChange: (Float, Float, Float) -> Unit,
    onCalibrateAll: () -> Unit,
    onCalibrateAzimuth: () -> Unit,
    onCalibrateTilt: () -> Unit,
    hasGps: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "⚓ Marine Configuration",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Device-to-vehicle calibration. Adjust mounting angles or use automatic calibration.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Calibration dials — side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CalibrationDial(
                    label = "Screen Twist (α)",
                    value = calibrationAlphaDeg,
                    onValueChange = { v -> onCalibrationAnglesChange(v, calibrationBetaDeg, calibrationGammaDeg) },
                    min = -180f,
                    max = 180f,
                    modifier = Modifier.weight(1f)
                )

                CalibrationDial(
                    label = "Tilt (β)",
                    value = calibrationBetaDeg,
                    onValueChange = { v -> onCalibrationAnglesChange(calibrationAlphaDeg, v, calibrationGammaDeg) },
                    min = 0f,
                    max = 180f,
                    modifier = Modifier.weight(1f)
                )

                CalibrationDial(
                    label = "Heading Offset (γ)",
                    value = calibrationGammaDeg,
                    onValueChange = { v -> onCalibrationAnglesChange(calibrationAlphaDeg, calibrationBetaDeg, v) },
                    min = -180f,
                    max = 180f,
                    modifier = Modifier.weight(1f)
                )
            }

            // Calibration buttons
            Text(
                text = "Automatic Calibration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCalibrateAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Calibrate All")
                }

                Button(
                    onClick = onCalibrateAzimuth,
                    enabled = hasGps,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Azimuth Only")
                }

                Button(
                    onClick = onCalibrateTilt,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Twist/Tilt Only")
                }
            }

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

@Composable
private fun CalibrationDial(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float,
    max: Float,
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
        
        // Circular dial — clickable and draggable to adjust
        Canvas(
            modifier = Modifier
                .size(100.dp)
                .pointerInput(onValueChange, min, max) {
                    detectDragGestures { change, _ ->
                        val x = change.position.x - size.width / 2
                        val y = change.position.y - size.height / 2
                        val angleRad = atan2(y, x)
                        // atan2 gives -π to π, convert to 0-360°
                        var angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
                        if (angleDeg < 0) angleDeg += 360
                        
                        // Map 0-360° to the min-max range
                        val normalized = angleDeg / 360f
                        val newValue = min + normalized * (max - min)
                        val finalValue = newValue.coerceIn(min, max)
                        android.util.Log.d("CalibrationDial", "$label: angle=$angleDeg° → value=$finalValue")
                        onValueChange(finalValue)
                    }
                }
        ) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val circleRadius = size.minDimension / 2 - 4.dp.toPx()
            
            // Draw outer circle
            drawCircle(
                color = outlineColor,
                radius = circleRadius,
                center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                style = Stroke(width = 2.dp.toPx())
            )
            
            // Draw angle indicator
            val normalizedValue = (value - min) / (max - min)
            val angleRad = Math.toRadians(normalizedValue * 360.0)
            val indicatorRadius = circleRadius - 10.dp.toPx()
            val endX = centerX + (indicatorRadius * cos(angleRad)).toFloat()
            val endY = centerY + (indicatorRadius * sin(angleRad)).toFloat()
            
            drawLine(
                color = primaryColor,
                start = androidx.compose.ui.geometry.Offset(centerX, centerY),
                end = androidx.compose.ui.geometry.Offset(endX, endY),
                strokeWidth = 3.dp.toPx()
            )
            
            // Draw cardinal point markers
            val markerRadius = circleRadius
            listOf(0.0, 90.0, 180.0, 270.0).forEach { angle ->
                val rad = Math.toRadians(angle)
                val x = centerX + (markerRadius * cos(rad)).toFloat()
                val y = centerY + (markerRadius * sin(rad)).toFloat()
                drawCircle(
                    color = outlineColor,
                    radius = 2.5.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
            }
        }
    }
}

private fun formatAngle(degrees: Float): String {
    return if (degrees == degrees.toLong().toFloat()) {
        degrees.toLong().toString()
    } else {
        String.format("%.1f", degrees)
    }
}

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

@Composable
fun DataTransmissionCard(
    sendLocation: Boolean,
    sendHeading: Boolean,
    sendPressure: Boolean,
    locationIntervalMs: Long,
    sensorIntervalMs: Long,
    onSendLocationChange: (Boolean) -> Unit,
    onSendHeadingChange: (Boolean) -> Unit,
    onSendPressureChange: (Boolean) -> Unit,
    onLocationIntervalChange: (Long) -> Unit,
    onSensorIntervalChange: (Long) -> Unit,
) {
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
            Text(
                text = "Data Transmission",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Choose which data types to transmit to SignalK server:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Location checkbox + interval
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = sendLocation,
                    onCheckedChange = onSendLocationChange
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Location Data",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "GPS position, course, and speed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IntervalDropdown(
                    options = LOCATION_INTERVAL_OPTIONS,
                    selected = locationIntervalMs,
                    onSelected = onLocationIntervalChange,
                    enabled = sendLocation
                )
            }
            
            // Heading checkbox + interval
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = sendHeading,
                    onCheckedChange = onSendHeadingChange
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Heading Data",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Magnetic and true compass heading",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IntervalDropdown(
                    options = SENSOR_INTERVAL_OPTIONS,
                    selected = sensorIntervalMs,
                    onSelected = onSensorIntervalChange,
                    enabled = sendHeading
                )
            }
            
            // Pressure checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = sendPressure,
                    onCheckedChange = onSendPressureChange
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Atmospheric Pressure",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Barometric pressure sensor data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
    val label = options.find { it.first == selected }?.second ?: "${selected} ms"

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
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
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
