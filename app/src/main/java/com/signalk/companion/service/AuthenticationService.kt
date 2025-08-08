package com.signalk.companion.service

import com.signalk.companion.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthenticationService @Inject constructor() {
    
    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    
    private val json = Json { ignoreUnknownKeys = true }
    
    suspend fun login(serverUrl: String, username: String, password: String): Result<LoginResponse> {
        return try {
            _authState.value = _authState.value.copy(isLoading = true, error = null)
            
            // Use IO dispatcher for network operations
            withContext(Dispatchers.IO) {
                val loginUrl = "${serverUrl.removeSuffix("/")}/signalk/v1/auth/login"
                val loginRequest = LoginRequest(username, password)
                
                val url = URL(loginUrl)
                val connection = url.openConnection() as HttpURLConnection
                
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                
                // Send login request
                val requestBody = json.encodeToString(loginRequest)
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody)
                    writer.flush()
                }
                
                val responseCode = connection.responseCode
                val responseBody = if (responseCode == 200) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                }
                
                when (responseCode) {
                    200 -> {
                        val loginResponse = json.decodeFromString<LoginResponse>(responseBody)
                        
                        _authState.value = _authState.value.copy(
                            isAuthenticated = true,
                            token = loginResponse.token,
                            username = username,
                            password = password,  // Store temporarily for re-authentication
                            serverUrl = serverUrl,
                            isLoading = false,
                            error = null
                        )
                        
                        Result.success(loginResponse)
                    }
                    401 -> {
                        _authState.value = _authState.value.copy(
                            isLoading = false, 
                            error = "Invalid username or password"
                        )
                        Result.failure(Exception("Invalid credentials"))
                    }
                    501 -> {
                        _authState.value = _authState.value.copy(
                            isLoading = false, 
                            error = "Server does not support authentication"
                        )
                        Result.failure(Exception("Authentication not supported"))
                    }
                    else -> {
                        _authState.value = _authState.value.copy(
                            isLoading = false, 
                            error = "Login failed: $responseCode"
                        )
                        Result.failure(Exception("HTTP $responseCode: $responseBody"))
                    }
                }
            } // Close withContext(Dispatchers.IO)
        } catch (e: java.net.UnknownHostException) {
            val error = "Cannot resolve hostname: ${e.message ?: "Unknown host"}"
            _authState.value = _authState.value.copy(
                isLoading = false, 
                error = error
            )
            Result.failure(Exception(error))
        } catch (e: java.net.ConnectException) {
            val error = "Cannot connect to server: ${e.message ?: "Connection refused"}"
            _authState.value = _authState.value.copy(
                isLoading = false, 
                error = error
            )
            Result.failure(Exception(error))
        } catch (e: java.net.SocketTimeoutException) {
            val error = "Connection timeout: Server not responding"
            _authState.value = _authState.value.copy(
                isLoading = false, 
                error = error
            )
            Result.failure(Exception(error))
        } catch (e: java.io.IOException) {
            val error = "Network error: ${e.message ?: "I/O error"}"
            _authState.value = _authState.value.copy(
                isLoading = false, 
                error = error
            )
            Result.failure(Exception(error))
        } catch (e: Exception) {
            val error = "Login failed: ${e.javaClass.simpleName} - ${e.message ?: "Unknown error"}"
            _authState.value = _authState.value.copy(
                isLoading = false, 
                error = error
            )
            Result.failure(e)
        }
    }
    
    suspend fun logout(): Result<Unit> {
        return try {
            val currentState = _authState.value
            val serverUrl = currentState.serverUrl
            val token = currentState.token
            
            if (serverUrl != null && token != null) {
                withContext(Dispatchers.IO) {
                    val logoutUrl = "${serverUrl.removeSuffix("/")}/signalk/v1/auth/logout"
                    val url = URL(logoutUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    
                    connection.apply {
                        requestMethod = "PUT"
                        setRequestProperty("Authorization", "Bearer $token")
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    
                    // We don't really care about the response for logout
                    connection.responseCode
                }
            }
            
            // Always clear local auth state
            _authState.value = AuthState()
            Result.success(Unit)
        } catch (e: Exception) {
            // Still clear local state even if server logout failed
            _authState.value = AuthState()
            Result.success(Unit)
        }
    }
    
    fun getAuthToken(): String? {
        return _authState.value.token
    }
    
    suspend fun tryRefreshToken(): Result<String?> {
        return try {
            val currentState = _authState.value
            
            // Since this server doesn't support token refresh/validation,
            // we'll attempt to re-authenticate with stored credentials to get a fresh token
            if (currentState.isAuthenticated && 
                currentState.serverUrl != null && 
                currentState.username != null &&
                currentState.password != null) {
                
                // Re-login using stored credentials
                val loginResult = login(currentState.serverUrl, currentState.username, currentState.password)
                if (loginResult.isSuccess) {
                    // Login successful, return the new token
                    Result.success(_authState.value.token)
                } else {
                    // Login failed, return null to indicate re-authentication needed
                    Result.success(null)
                }
            } else {
                // No stored credentials available, manual re-authentication required
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun clearError() {
        _authState.value = _authState.value.copy(error = null)
    }
}
