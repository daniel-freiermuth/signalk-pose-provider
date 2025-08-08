package com.signalk.companion.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // Request permissions
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )
    
    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SignalK Navigation Provider") }
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
            // Connection Status Card
            ConnectionStatusCard(
                isConnected = uiState.isConnected,
                serverUrl = uiState.serverUrl,
                transmissionProtocol = uiState.transmissionProtocol,
                onServerUrlChange = viewModel::updateServerUrl,
                onProtocolChange = viewModel::updateTransmissionProtocol
            )
            
            // Authentication Card (integrated login)
            AuthenticationCard(
                isAuthenticated = uiState.isAuthenticated,
                username = uiState.username,
                serverUrl = uiState.serverUrl,
                isLoggingIn = uiState.isLoggingIn,
                onLogin = { username, password -> viewModel.login(username, password) },
                onLogout = { viewModel.logout() }
            )
            
            // Control Card
            ControlCard(
                isStreaming = uiState.isStreaming,
                onStartStop = {
                    if (uiState.isStreaming) {
                        viewModel.stopStreaming()
                    } else {
                        if (permissionsState.allPermissionsGranted) {
                            viewModel.startStreaming(context)
                        }
                    }
                },
                permissionsGranted = permissionsState.allPermissionsGranted
            )
            
            // Update Rate Configuration Card
            UpdateRateCard(
                locationUpdateRate = uiState.locationUpdateRate,
                sensorUpdateRate = uiState.sensorUpdateRate,
                onLocationRateChange = viewModel::updateLocationUpdateRate,
                onSensorRateChange = viewModel::updateSensorRate
            )
            
            // Marine Configuration Card
            MarineConfigCard(
                deviceOrientation = uiState.deviceOrientation,
                compassTiltCorrection = uiState.compassTiltCorrection,
                headingOffset = uiState.headingOffset,
                onDeviceOrientationChange = viewModel::updateDeviceOrientation,
                onTiltCorrectionChange = viewModel::updateCompassTiltCorrection,
                onHeadingOffsetChange = viewModel::updateHeadingOffset
            )
            
            // Error Card
            uiState.error?.let { error ->
                ErrorCard(
                    error = error,
                    onDismiss = { /* Could add a dismiss function to ViewModel */ }
                )
            }
            
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionStatusCard(
    isConnected: Boolean,
    serverUrl: String,
    transmissionProtocol: TransmissionProtocol,
    onServerUrlChange: (String) -> Unit,
    onProtocolChange: (TransmissionProtocol) -> Unit
) {
    var protocolDropdownExpanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Connection",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.100:3000") },
                modifier = Modifier.fillMaxWidth()
            )
            
            // Protocol Selection
            ExposedDropdownMenuBox(
                expanded = protocolDropdownExpanded,
                onExpandedChange = { protocolDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = transmissionProtocol.displayName,
                    onValueChange = {},
                    label = { Text("Protocol") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolDropdownExpanded) },
                    readOnly = true,
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                
                ExposedDropdownMenu(
                    expanded = protocolDropdownExpanded,
                    onDismissRequest = { protocolDropdownExpanded = false }
                ) {
                    TransmissionProtocol.values().forEach { protocol ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(protocol.displayName)
                                    Text(
                                        text = protocol.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onProtocolChange(protocol)
                                protocolDropdownExpanded = false
                            }
                        )
                    }
                }
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statusColor = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                Card(
                    colors = CardDefaults.cardColors(containerColor = statusColor),
                    modifier = Modifier.size(12.dp)
                ) {}
                Text(
                    text = if (isConnected) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
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
                enabled = permissionsGranted,
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
                
                Divider(modifier = Modifier.padding(vertical = 4.dp))
            } ?: run {
                Text(
                    text = "No GPS data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Divider(modifier = Modifier.padding(vertical = 4.dp))
            }
            
            // Device Sensors Section
            sensorData?.let { sensor ->
                var hasOrientationData = false
                var hasEnvironmentalData = false
                var hasDeviceData = false
                
                // Navigation/Orientation Data
                if (sensor.magneticHeading != null || sensor.trueHeading != null || 
                    sensor.roll != null || sensor.pitch != null || sensor.yaw != null || 
                    sensor.rateOfTurn != null) {
                    hasOrientationData = true
                    Text(
                        text = "Device Orientation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    sensor.magneticHeading?.let { heading ->
                        SensorDataRow("Magnetic Heading", "${String.format("%.1f", Math.toDegrees(heading.toDouble()))}°")
                    }
                    sensor.trueHeading?.let { heading ->
                        SensorDataRow("True Heading", "${String.format("%.1f", Math.toDegrees(heading.toDouble()))}°")
                    }
                    sensor.roll?.let { roll ->
                        SensorDataRow("Roll", "${String.format("%.1f", Math.toDegrees(roll.toDouble()))}°")
                    }
                    sensor.pitch?.let { pitch ->
                        SensorDataRow("Pitch", "${String.format("%.1f", Math.toDegrees(pitch.toDouble()))}°")
                    }
                    sensor.yaw?.let { yaw ->
                        SensorDataRow("Yaw", "${String.format("%.1f", Math.toDegrees(yaw.toDouble()))}°")
                    }
                    sensor.rateOfTurn?.let { rate ->
                        SensorDataRow("Rate of Turn", "${String.format("%.2f", Math.toDegrees(rate.toDouble()))}°/s")
                    }
                }
                
                // Environmental Data
                if (sensor.pressure != null || sensor.temperature != null || 
                    sensor.relativeHumidity != null || sensor.illuminance != null) {
                    if (hasOrientationData) Divider(modifier = Modifier.padding(vertical = 4.dp))
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
                    sensor.illuminance?.let { light ->
                        SensorDataRow("Light Level", "${String.format("%.0f", light)} Lux")
                    }
                }
                
                // Device/Battery Data
                if (sensor.batteryLevel != null || sensor.batteryVoltage != null) {
                    if (hasOrientationData || hasEnvironmentalData) Divider(modifier = Modifier.padding(vertical = 4.dp))
                    hasDeviceData = true
                    Text(
                        text = "Device Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    sensor.batteryLevel?.let { level ->
                        SensorDataRow("Battery Level", "${String.format("%.0f", level * 100)}%")
                    }
                    sensor.batteryVoltage?.let { voltage ->
                        SensorDataRow("Battery Voltage", "${String.format("%.2f", voltage)} V")
                    }
                }
                
                if (!hasOrientationData && !hasEnvironmentalData && !hasDeviceData) {
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
        }
    }
}

@Composable
fun AuthenticationCard(
    isAuthenticated: Boolean,
    username: String?,
    serverUrl: String,
    isLoggingIn: Boolean = false,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var loginUsername by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    
    // Close the form only when authentication succeeds
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            expanded = false
            loginPassword = "" // Clear password for security
        }
    }
    
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
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Authentication",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isAuthenticated) "✅ Authenticated as: $username" else "🔓 Not authenticated",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isAuthenticated) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (isAuthenticated) {
                    OutlinedButton(onClick = onLogout) {
                        Text("Logout")
                    }
                } else {
                    Button(
                        onClick = { expanded = !expanded },
                        enabled = !isLoggingIn
                    ) {
                        Text(if (expanded) "Cancel" else "Login")
                    }
                }
            }
            
            // Expandable login form
            if (expanded && !isAuthenticated) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Server: $serverUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    OutlinedTextField(
                        value = loginUsername,
                        onValueChange = { loginUsername = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = loginPassword,
                        onValueChange = { loginPassword = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(
                                    text = if (passwordVisible) "👁" else "🔒",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        },
                        singleLine = true
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { expanded = false },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoggingIn
                        ) {
                            Text("Cancel")
                        }
                        
                        Button(
                            onClick = {
                                if (loginUsername.isNotBlank() && loginPassword.isNotBlank()) {
                                    onLogin(loginUsername, loginPassword)
                                    // Don't collapse form here - let LaunchedEffect handle it on success
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = loginUsername.isNotBlank() && loginPassword.isNotBlank() && !isLoggingIn
                        ) {
                            if (isLoggingIn) {
                                Text("Logging in...")
                            } else {
                                Text("Login")
                            }
                        }
                    }
                }
            } else if (!isAuthenticated) {
                Text(
                    text = "Optional: Login to authenticate with your SignalK server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                "humidity" to "Relative Humidity",
                "light" to "Light Sensor"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateRateCard(
    locationUpdateRate: UpdateRate,
    sensorUpdateRate: UpdateRate,
    onLocationRateChange: (UpdateRate) -> Unit,
    onSensorRateChange: (UpdateRate) -> Unit
) {
    var locationDropdownExpanded by remember { mutableStateOf(false) }
    var sensorDropdownExpanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Update Rate Configuration",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Configure how frequently location and sensor data is collected and transmitted. Lower rates save battery but provide less frequent updates.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Location Update Rate
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Location Update Rate",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                
                ExposedDropdownMenuBox(
                    expanded = locationDropdownExpanded,
                    onExpandedChange = { locationDropdownExpanded = !locationDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = "${locationUpdateRate.displayName} (${locationUpdateRate.description})",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Location Rate") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = locationDropdownExpanded
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = locationDropdownExpanded,
                        onDismissRequest = { locationDropdownExpanded = false }
                    ) {
                        UpdateRate.values().forEach { rate ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = rate.displayName,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = rate.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onLocationRateChange(rate)
                                    locationDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            // Sensor Update Rate
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Sensor Update Rate",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                
                ExposedDropdownMenuBox(
                    expanded = sensorDropdownExpanded,
                    onExpandedChange = { sensorDropdownExpanded = !sensorDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = "${sensorUpdateRate.displayName} (${sensorUpdateRate.description})",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Sensor Rate") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = sensorDropdownExpanded
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = sensorDropdownExpanded,
                        onDismissRequest = { sensorDropdownExpanded = false }
                    ) {
                        UpdateRate.values().forEach { rate ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = rate.displayName,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = rate.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onSensorRateChange(rate)
                                    sensorDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            // Battery Impact Indicator
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "🔋",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Battery Impact: ${getBatteryImpact(locationUpdateRate, sensorUpdateRate)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = "Current configuration will update location every ${locationUpdateRate.intervalMs / 1000}s and sensors every ${sensorUpdateRate.intervalMs / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

private fun getBatteryImpact(locationRate: UpdateRate, sensorRate: UpdateRate): String {
    val combinedScore = (locationRate.ordinal + sensorRate.ordinal) / 2.0
    return when {
        combinedScore < 0.5 -> "Very High"
        combinedScore < 1.5 -> "High"
        combinedScore < 2.5 -> "Medium"
        else -> "Low"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarineConfigCard(
    deviceOrientation: DeviceOrientation,
    compassTiltCorrection: Boolean,
    headingOffset: Float,
    onDeviceOrientationChange: (DeviceOrientation) -> Unit,
    onTiltCorrectionChange: (Boolean) -> Unit,
    onHeadingOffsetChange: (Float) -> Unit
) {
    var orientationDropdownExpanded by remember { mutableStateOf(false) }
    var offsetText by remember { mutableStateOf(headingOffset.toString()) }
    
    // Update offset text when headingOffset changes externally
    LaunchedEffect(headingOffset) {
        offsetText = headingOffset.toString()
    }
    
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
                text = "Configure device orientation and compass settings for boat mounting",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Device Orientation Selection
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Device Orientation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                
                ExposedDropdownMenuBox(
                    expanded = orientationDropdownExpanded,
                    onExpandedChange = { orientationDropdownExpanded = !orientationDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = "${deviceOrientation.displayName} (${deviceOrientation.description})",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Mounting Orientation") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = orientationDropdownExpanded
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = orientationDropdownExpanded,
                        onDismissRequest = { orientationDropdownExpanded = false }
                    ) {
                        DeviceOrientation.values().forEach { orientation ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = orientation.displayName,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = orientation.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onDeviceOrientationChange(orientation)
                                    orientationDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            // Compass Tilt Correction Toggle
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Compass Tilt Correction",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Compensate for boat heel and pitch",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (compassTiltCorrection) "✓ Enabled - More accurate heading when boat tilts" 
                                  else "✗ Disabled - Basic compass reading only",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (compassTiltCorrection) MaterialTheme.colorScheme.primary 
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Switch(
                        checked = compassTiltCorrection,
                        onCheckedChange = onTiltCorrectionChange
                    )
                }
            }
            
            // Heading Offset Configuration
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Heading Offset Correction",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = "Correct for device mounting angle relative to boat centerline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = { 
                            offsetText = it
                            // Try to parse and update immediately
                            it.toFloatOrNull()?.let { value ->
                                if (value >= -180f && value <= 180f) {
                                    onHeadingOffsetChange(value)
                                }
                            }
                        },
                        label = { Text("Offset (°)") },
                        placeholder = { Text("0.0") },
                        suffix = { Text("°") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    
                    Text(
                        text = "Range: -180° to +180°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Quick preset buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(-15f, -10f, -5f, 0f, 5f, 10f, 15f)
                    presets.forEach { preset ->
                        OutlinedButton(
                            onClick = { 
                                onHeadingOffsetChange(preset)
                                offsetText = preset.toString()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (preset == 0f) "0°" else "${preset.toInt()}°",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            
            // Configuration Summary
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "🧭",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Current Configuration",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Text(
                        text = "• Orientation: ${deviceOrientation.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    
                    Text(
                        text = "• Tilt correction: ${if (compassTiltCorrection) "Enabled" else "Disabled"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    
                    Text(
                        text = "• Heading offset: ${if (headingOffset == 0f) "None" else "${headingOffset}°"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    
                    if (headingOffset != 0f) {
                        Text(
                            text = "Example: Device shows 015°, boat heading = 015° ${if (headingOffset > 0) "+" else ""}${headingOffset}° = ${String.format("%.0f", (15 + headingOffset + 360) % 360)}°",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
