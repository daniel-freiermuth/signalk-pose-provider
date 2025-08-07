package com.signalk.companion.service

import com.signalk.companion.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                    val tokenExpiry = System.currentTimeMillis() + (loginResponse.timeToLive * 1000)
                    
                    _authState.value = _authState.value.copy(
                        isAuthenticated = true,
                        token = loginResponse.token,
                        tokenExpiry = tokenExpiry,
                        username = username,
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
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(
                isLoading = false, 
                error = "Connection failed: ${e.message}"
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
            
            // Always clear local auth state
            _authState.value = AuthState()
            Result.success(Unit)
        } catch (e: Exception) {
            // Still clear local state even if server logout failed
            _authState.value = AuthState()
            Result.success(Unit)
        }
    }
    
    suspend fun validateToken(): Result<String> {
        return try {
            val currentState = _authState.value
            val serverUrl = currentState.serverUrl
            val token = currentState.token
            
            if (serverUrl == null || token == null) {
                return Result.failure(Exception("No token to validate"))
            }
            
            val validateUrl = "${serverUrl.removeSuffix("/")}/signalk/v1/auth/validate"
            val url = URL(validateUrl)
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
            }
            
            val responseCode = connection.responseCode
            
            when (responseCode) {
                200 -> {
                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    // For HTTP validation, we might get a new token in response
                    // For now, we'll assume the current token is still valid
                    Result.success(token)
                }
                401 -> {
                    // Token is invalid, clear auth state
                    _authState.value = AuthState()
                    Result.failure(Exception("Token expired"))
                }
                else -> {
                    Result.failure(Exception("Token validation failed: $responseCode"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun isTokenExpired(): Boolean {
        val expiry = _authState.value.tokenExpiry ?: return true
        return System.currentTimeMillis() >= expiry - 60000 // Expire 1 minute early for safety
    }
    
    fun getAuthToken(): String? {
        return if (isTokenExpired()) {
            null
        } else {
            _authState.value.token
        }
    }
    
    fun clearError() {
        _authState.value = _authState.value.copy(error = null)
    }
}
