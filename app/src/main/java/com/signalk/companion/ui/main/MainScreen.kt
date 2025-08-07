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
                title = { Text("SignalK Companion") }
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
            
            // Error Card
            uiState.error?.let { error ->
                ErrorCard(
                    error = error,
                    onDismiss = { /* Could add a dismiss function to ViewModel */ }
                )
            }
            
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
            
            locationData?.let { location ->
                SensorDataRow("Latitude", "${location.latitude}")
                SensorDataRow("Longitude", "${location.longitude}")
                SensorDataRow("Speed", "${String.format("%.2f", location.speed)} m/s")
                SensorDataRow("Bearing", "${String.format("%.1f", location.bearing)}°")
                SensorDataRow("Accuracy", "${String.format("%.1f", location.accuracy)} m")
            } ?: run {
                Text(
                    text = "No location data",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            sensorData?.let { sensor ->
                sensor.pressure?.let { pressure ->
                    SensorDataRow("Pressure", "${String.format("%.2f", pressure)} hPa")
                }
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
