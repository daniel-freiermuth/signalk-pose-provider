package com.signalk.companion.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay

private const val SAVE_SUCCESS_DISPLAY_MS = 2000L

/** Username and password as currently typed, plus where authentication stands. */
private data class CredentialsState(
    val username: String,
    val password: String,
    val isAuthenticated: Boolean,
    val isLoggingIn: Boolean
)

private data class CredentialsActions(
    val onUsernameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onTestConnection: () -> Unit,
    val onLogout: () -> Unit
)

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Initialize settings when the screen is first shown
    LaunchedEffect(Unit) {
        viewModel.initializeSettings(context)
    }

    // Clear save success after showing
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            delay(SAVE_SUCCESS_DISPLAY_MS)
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            SettingsTopBar(
                isSaving = uiState.isSaving,
                onNavigateBack = onNavigateBack,
                onSave = { viewModel.saveSettings(context) }
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
            if (uiState.saveSuccess) {
                SaveSuccessCard()
            }

            uiState.error?.let { error ->
                SettingsErrorCard(error = error, onDismiss = { viewModel.clearError() })
            }

            ServerConfigCard(
                serverUrl = uiState.serverUrl,
                vesselId = uiState.vesselId,
                onServerUrlChange = viewModel::updateServerUrl,
                onVesselIdChange = viewModel::updateVesselId
            )

            CredentialsCard(
                state = uiState.credentialsState(),
                actions = CredentialsActions(
                    onUsernameChange = viewModel::updateUsername,
                    onPasswordChange = viewModel::updatePassword,
                    onTestConnection = { viewModel.testConnection(context) },
                    onLogout = viewModel::logout
                )
            )

            SaveSettingsButton(
                isSaving = uiState.isSaving,
                onClick = { viewModel.saveSettings(context) }
            )
        }
    }
}

private fun SettingsUiState.credentialsState(): CredentialsState = CredentialsState(
    username = username,
    password = password,
    isAuthenticated = isAuthenticated,
    isLoggingIn = isLoggingIn
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
    isSaving: Boolean,
    onNavigateBack: () -> Unit,
    onSave: () -> Unit
) {
    TopAppBar(
        title = { Text("Settings") },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            // Save button in app bar
            IconButton(
                onClick = onSave,
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Save"
                    )
                }
            }
        }
    )
}

@Composable
private fun SaveSuccessCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "Settings saved successfully",
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun SettingsErrorCard(error: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun SaveSettingsButton(isSaving: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isSaving
    ) {
        if (isSaving) {
            ButtonProgressIndicator()
        }
        Text("Save Settings")
    }
}

/** Small spinner shown inside a button, followed by the gap before its label. */
@Composable
private fun ButtonProgressIndicator() {
    CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.onPrimary
    )
    Spacer(modifier = Modifier.width(8.dp))
}

@Composable
private fun ServerConfigCard(
    serverUrl: String,
    vesselId: String,
    onServerUrlChange: (String) -> Unit,
    onVesselIdChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Server Configuration",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("https://signalk.example.com:3000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text("Full URL including protocol (http/https) and port")
                }
            )

            OutlinedTextField(
                value = vesselId,
                onValueChange = onVesselIdChange,
                label = { Text("Vessel ID") },
                placeholder = { Text("self") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text("Used in SignalK context: vessels.$vesselId")
                }
            )
        }
    }
}

@Composable
private fun CredentialsCard(state: CredentialsState, actions: CredentialsActions) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AuthenticationHeader(isAuthenticated = state.isAuthenticated)

            OutlinedTextField(
                value = state.username,
                onValueChange = actions.onUsernameChange,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.isLoggingIn
            )

            PasswordField(
                password = state.password,
                onPasswordChange = actions.onPasswordChange,
                enabled = !state.isLoggingIn
            )

            CredentialsButtons(state = state, actions = actions)
        }
    }
}

@Composable
private fun AuthenticationHeader(isAuthenticated: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Authentication",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Status indicator
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isAuthenticated) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Text(
                text = if (isAuthenticated) "Authenticated" else "Not authenticated",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (isAuthenticated) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun PasswordField(
    password: String,
    onPasswordChange: (String) -> Unit,
    enabled: Boolean
) {
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Password") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Text(
                    text = if (passwordVisible) "👁" else "🔒",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        enabled = enabled,
        supportingText = {
            Text("Credentials are stored locally on your device")
        }
    )
}

@Composable
private fun CredentialsButtons(state: CredentialsState, actions: CredentialsActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.isAuthenticated) {
            OutlinedButton(
                onClick = actions.onLogout,
                modifier = Modifier.weight(1f)
            ) {
                Text("Logout")
            }
        }

        Button(
            onClick = actions.onTestConnection,
            modifier = Modifier.weight(1f),
            enabled = !state.isLoggingIn &&
                state.username.isNotBlank() &&
                state.password.isNotBlank()
        ) {
            if (state.isLoggingIn) {
                ButtonProgressIndicator()
                Text("Testing...")
            } else {
                Text("Test Connection")
            }
        }
    }
}
