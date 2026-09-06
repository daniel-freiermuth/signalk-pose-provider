package com.signalk.companion.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val token: String                    // JWT token (required)
)

// Data class for storing authentication state
data class AuthState(
    val isAuthenticated: Boolean = false,
    val token: String? = null,
    val username: String? = null,
    val password: String? = null,     // Stored temporarily in memory for re-authentication
    val serverUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)
