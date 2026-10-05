package com.signalk.companion.service

import com.signalk.companion.data.model.LoginResponse
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Result of [AuthenticationService.login]. */
sealed interface LoginOutcome {
    data class Success(val response: LoginResponse) : LoginOutcome
    data class Failure(val error: LoginError) : LoginOutcome
}

/**
 * Why a login did not produce a token, split at the type level into the server (or the
 * configuration) refusing it — retrying the same request cannot succeed — and the server
 * being unreachable or misbehaving, which may clear up on its own.
 */
sealed interface LoginError {
    /** Message shown to the user. */
    val userMessage: String

    /** Retrying with the same URL and credentials cannot succeed. */
    sealed interface Refusal : LoginError

    /** The server could not be reached or did not answer as expected; a retry may succeed. */
    sealed interface Infrastructure : LoginError

    data class InvalidServerUrl(val serverUrl: String) : Refusal {
        override val userMessage get() = "Invalid server URL: $serverUrl"
    }

    data object InvalidCredentials : Refusal {
        override val userMessage get() = "Invalid username or password"
    }

    data object AuthNotSupported : Refusal {
        override val userMessage get() = "Server does not support authentication"
    }

    /** Any status other than 200, 401 and 501, e.g. 503 while the server is starting. */
    data class HttpError(val code: Int) : Infrastructure {
        override val userMessage get() = "Login failed: $code"
    }

    data class Network(val cause: IOException) : Infrastructure {
        override val userMessage get() = when (cause) {
            is UnknownHostException ->
                "Cannot resolve hostname: ${cause.message ?: "Unknown host"}"
            is ConnectException ->
                "Cannot connect to server: ${cause.message ?: "Connection refused"}"
            is SocketTimeoutException -> "Connection timeout: Server not responding"
            else -> "Network error: ${cause.message ?: "I/O error"}"
        }
    }

    /** The server answered 200 with a body that is not a login response. */
    data class MalformedResponse(val cause: IllegalArgumentException) : Infrastructure {
        override val userMessage get() =
            "Login failed: ${cause.javaClass.simpleName} - ${cause.message ?: "Unknown error"}"
    }
}

/** Result of [AuthenticationService.tryRefreshToken]. */
sealed interface RefreshOutcome {
    data class Refreshed(val token: String) : RefreshOutcome

    /** No credentials are stored; a manual login is required. */
    data object NoCredentials : RefreshOutcome

    /** The server refused the stored credentials; retrying them cannot succeed. */
    data class Refused(val error: LoginError.Refusal) : RefreshOutcome

    /** The server could not be asked; the same credentials may succeed later. */
    data class Unreachable(val error: LoginError.Infrastructure) : RefreshOutcome
}
