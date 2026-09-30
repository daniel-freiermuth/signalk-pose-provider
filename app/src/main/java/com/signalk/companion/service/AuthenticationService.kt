package com.signalk.companion.service

import android.util.Log
import com.signalk.companion.data.model.AuthState
import com.signalk.companion.data.model.LoginRequest
import com.signalk.companion.data.model.LoginResponse
import com.signalk.companion.di.IoDispatcher
import com.signalk.companion.util.CanIgnoreReturnValue
import com.signalk.companion.util.UrlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthenticationService @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    companion object {
        private const val TAG = "AuthenticationService"
        private const val LOGIN_TIMEOUT_MS = 10_000
        private const val LOGOUT_TIMEOUT_MS = 5_000
    }

    /** Status code and body of an HTTP exchange. */
    private data class HttpResponse(val code: Int, val body: String)

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private fun setAuthError(errorMessage: String) {
        _authState.update { currentState ->
            currentState.copy(
                isAuthenticated = false,
                token = null,
                isLoading = false,
                error = errorMessage
            )
        }
    }

    /** Records [errorMessage] in the auth state and returns [cause] as the failed result. */
    private fun <T> failLogin(errorMessage: String, cause: Throwable): Result<T> {
        setAuthError(errorMessage)
        return Result.failure(cause)
    }

    /**
     * Logs in and stores the credentials for [tryRefreshToken]. The outcome, including the
     * user-facing error, is also published in [authState]; callers that only drive the UI
     * may therefore ignore the returned [Result].
     */
    @CanIgnoreReturnValue
    suspend fun login(
        serverUrl: String,
        username: String,
        password: String
    ): Result<LoginResponse> {
        _authState.update { it.copy(isLoading = true, error = null) }

        val baseUrl = UrlParser.parseUrl(serverUrl)?.toUrlString()
        if (baseUrl == null) {
            val error = "Invalid server URL: $serverUrl"
            return failLogin(error, IllegalArgumentException(error))
        }

        // Store credentials immediately so tryRefreshToken() can retry
        // even if the network call below fails (e.g. server down at startup).
        _authState.update {
            it.copy(serverUrl = baseUrl, username = username, password = password)
        }

        return try {
            val response = withContext(ioDispatcher) {
                postLogin(baseUrl, LoginRequest(username, password))
            }
            handleLoginResponse(response, baseUrl, username, password)
        } catch (e: IOException) {
            val error = networkErrorMessage(e)
            failLogin(error, IOException(error, e))
        } catch (e: IllegalArgumentException) {
            // kotlinx SerializationException is an IllegalArgumentException: the server
            // answered 200 with a body that is not a LoginResponse.
            failLogin(
                "Login failed: ${e.javaClass.simpleName} - ${e.message ?: "Unknown error"}",
                e
            )
        } catch (e: CancellationException) {
            // The caller went away mid-request. Not a login failure, but the shared state
            // must not stay "loading", or every later Settings screen shows a login in flight.
            _authState.update { it.copy(isLoading = false) }
            throw e
        }
    }

    /** Blocking POST of [loginRequest] to the server's login endpoint. */
    private fun postLogin(baseUrl: String, loginRequest: LoginRequest): HttpResponse {
        val connection = URL("$baseUrl/signalk/v1/auth/login").openConnection()
            as HttpURLConnection

        connection.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            doOutput = true
            connectTimeout = LOGIN_TIMEOUT_MS
            readTimeout = LOGIN_TIMEOUT_MS
        }

        val requestBody = json.encodeToString(loginRequest)
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(requestBody)
            writer.flush()
        }

        val responseCode = connection.responseCode
        val responseBody = if (responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
        }
        return HttpResponse(responseCode, responseBody)
    }

    private fun handleLoginResponse(
        response: HttpResponse,
        baseUrl: String,
        username: String,
        password: String
    ): Result<LoginResponse> = when (response.code) {
        HttpURLConnection.HTTP_OK -> {
            val loginResponse = json.decodeFromString<LoginResponse>(response.body)
            _authState.update {
                it.copy(
                    isAuthenticated = true,
                    token = loginResponse.token,
                    username = username,
                    password = password, // Store temporarily for re-authentication
                    serverUrl = baseUrl,
                    isLoading = false,
                    error = null
                )
            }
            Result.success(loginResponse)
        }
        HttpURLConnection.HTTP_UNAUTHORIZED ->
            failLogin("Invalid username or password", IOException("Invalid credentials"))
        HttpURLConnection.HTTP_NOT_IMPLEMENTED ->
            failLogin(
                "Server does not support authentication",
                IOException("Authentication not supported")
            )
        else ->
            failLogin(
                "Login failed: ${response.code}",
                IOException("HTTP ${response.code}: ${response.body}")
            )
    }

    private fun networkErrorMessage(e: IOException): String = when (e) {
        is UnknownHostException -> "Cannot resolve hostname: ${e.message ?: "Unknown host"}"
        is ConnectException -> "Cannot connect to server: ${e.message ?: "Connection refused"}"
        is SocketTimeoutException -> "Connection timeout: Server not responding"
        else -> "Network error: ${e.message ?: "I/O error"}"
    }

    suspend fun logout() {
        val currentState = _authState.value
        val serverUrl = currentState.serverUrl
        val token = currentState.token

        try {
            if (serverUrl != null && token != null) {
                try {
                    withContext(ioDispatcher) { putLogout(serverUrl, token) }
                } catch (e: IOException) {
                    // Still clear local state even if server logout failed
                    Log.w(TAG, "Server logout failed; clearing the local session anyway", e)
                }
            }
        } finally {
            // Always clear local auth state — also when the caller is cancelled mid-request,
            // since the server may already have invalidated the token.
            _authState.update { AuthState() }
        }
    }

    /** Blocking PUT to the server's logout endpoint; the response is irrelevant. */
    private fun putLogout(serverUrl: String, token: String) {
        val logoutUrl = "${serverUrl.removeSuffix("/")}/signalk/v1/auth/logout"
        val connection = URL(logoutUrl).openConnection() as HttpURLConnection

        connection.apply {
            requestMethod = "PUT"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = LOGOUT_TIMEOUT_MS
            readTimeout = LOGOUT_TIMEOUT_MS
        }

        // We don't really care about the response for logout
        connection.responseCode
    }

    fun getAuthToken(): String? {
        return _authState.value.token
    }

    fun hasStoredCredentials(): Boolean {
        val s = _authState.value
        return s.serverUrl != null && s.username != null && s.password != null
    }

    /**
     * Re-authenticate with stored credentials to get a fresh token.
     *
     * Returns the new token, or null when re-authentication failed or no credentials are
     * stored, meaning a manual login is required. We do NOT require isAuthenticated —
     * credentials may exist from a prior login attempt that failed due to the server being
     * temporarily unreachable.
     */
    suspend fun tryRefreshToken(): Result<String?> {
        val currentState = _authState.value
        val serverUrl = currentState.serverUrl
        val username = currentState.username
        val password = currentState.password
        if (serverUrl == null || username == null || password == null) {
            return Result.success(null)
        }

        val loginResult = login(serverUrl, username, password)
        return Result.success(if (loginResult.isSuccess) _authState.value.token else null)
    }

    fun clearError() {
        _authState.update { it.copy(error = null) }
    }
}
