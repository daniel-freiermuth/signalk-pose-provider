package com.signalk.companion.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val timeToLive: Long,     // Token validity in seconds
    val token: String         // JWT token
)

@Serializable
data class TokenValidationRequest(
    val requestId: String,
    val validate: ValidateToken
)

@Serializable
data class ValidateToken(
    val token: String
)

@Serializable
data class TokenValidationResponse(
    val requestId: String,
    val state: String,        // "COMPLETED"
    val result: Int,          // HTTP status code (200 = success)
    val validate: ValidateTokenResult? = null
)

@Serializable
data class ValidateTokenResult(
    val token: String         // New/renewed token
)

@Serializable
data class LogoutRequest(
    val requestId: String,
    val logout: LogoutToken
)

@Serializable
data class LogoutToken(
    val token: String
)

@Serializable
data class LogoutResponse(
    val requestId: String,
    val state: String,        // "COMPLETED"
    val result: Int           // HTTP status code (200 = success)
)

// Data class for storing authentication state
data class AuthState(
    val isAuthenticated: Boolean = false,
    val token: String? = null,
    val tokenExpiry: Long? = null,    // Timestamp when token expires
    val username: String? = null,
    val serverUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)
