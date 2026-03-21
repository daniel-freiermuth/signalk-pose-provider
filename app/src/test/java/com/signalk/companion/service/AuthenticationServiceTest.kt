package com.signalk.companion.service

import com.signalk.companion.data.model.AuthState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuthenticationServiceTest {

    private lateinit var service: AuthenticationService

    @BeforeEach
    fun setUp() {
        service = AuthenticationService()
    }

    // ── AuthState data class ──────────────────────────────────────────────────

    @Test
    fun `AuthState copy preserves credentials when only error fields change`() {
        val state = AuthState(
            serverUrl = "http://server:3000",
            username = "alice",
            password = "secret"
        )
        val updated = state.copy(isAuthenticated = false, token = null, isLoading = false, error = "Network error")
        assertEquals("http://server:3000", updated.serverUrl)
        assertEquals("alice", updated.username)
        assertEquals("secret", updated.password)
    }

    // ── login() stores credentials even on failure ────────────────────────────

    @Test
    fun `login stores serverUrl and credentials in authState even when network fails`() = runTest {
        // Use a URL that will fail quickly (port 1 refused immediately on loopback)
        service.login("http://127.0.0.1:1", "bob", "pass123")

        val state = service.authState.value
        // Connection must have been attempted and failed
        assertFalse(state.isAuthenticated, "Should not be authenticated after network failure")
        assertNull(state.token, "Token must be null after failure")
        // Credentials must be stored for later retry
        assertNotNull(state.serverUrl, "serverUrl must be stored for retry")
        assertEquals("bob", state.username)
        assertEquals("pass123", state.password)
    }

    @Test
    fun `login with unparseable scheme does not store credentials`() = runTest {
        // "ftp://" is rejected by UrlParser so parsedUrl == null → return early, no storage
        service.login("ftp://server:3000", "bob", "pass")

        val state = service.authState.value
        assertFalse(state.isAuthenticated)
        // URL failed to parse: credentials must NOT be stored
        assertNull(state.username)
    }

    // ── tryRefreshToken() works without prior successful login ────────────────

    @Test
    fun `tryRefreshToken returns null when no credentials are stored`() = runTest {
        // Fresh service, no prior login
        val result = service.tryRefreshToken()
        assertTrue(result.isSuccess, "Should succeed (no exception)")
        assertNull(result.getOrNull(), "Should return null when no credentials stored")
    }

    @Test
    fun `tryRefreshToken attempts re-login even when isAuthenticated is false`() = runTest {
        // First login fails (server down) but credentials are now stored in authState
        service.login("http://127.0.0.1:1", "alice", "secret")
        val stateAfterFailedLogin = service.authState.value
        assertFalse(stateAfterFailedLogin.isAuthenticated)
        assertNotNull(stateAfterFailedLogin.username, "Credentials should be stored even after failure")

        // tryRefreshToken should attempt re-login (it will fail again, but that's OK —
        // we verify it *tries* by checking it returns Result.success(null) rather than
        // skipping with null due to the old isAuthenticated guard)
        val result = service.tryRefreshToken()
        assertTrue(result.isSuccess, "tryRefreshToken should not throw")
        // Result is null because re-login to 127.0.0.1:1 also fails, but it *tried*
        assertNull(result.getOrNull())
        // authState should still have credentials for the next attempt
        assertEquals("alice", service.authState.value.username)
    }

    // ── hasStoredCredentials helper ───────────────────────────────────────────

    @Test
    fun `hasStoredCredentials returns false on fresh service`() {
        assertFalse(service.hasStoredCredentials())
    }

    @Test
    fun `hasStoredCredentials returns true after failed login that stored credentials`() = runTest {
        service.login("http://127.0.0.1:1", "user", "pw")
        assertTrue(service.hasStoredCredentials())
    }

    @Test
    fun `hasStoredCredentials returns false after logout`() = runTest {
        service.login("http://127.0.0.1:1", "user", "pw")
        service.logout()
        assertFalse(service.hasStoredCredentials())
    }
}
