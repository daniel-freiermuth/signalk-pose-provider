package com.signalk.companion.service

import com.signalk.companion.data.model.AuthState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AuthenticationServiceTest {

    private lateinit var service: AuthenticationService

    /** Real threads, for tests that need a request blocked while the test acts. */
    private val threads = Executors.newCachedThreadPool().asCoroutineDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher
    @BeforeEach
    fun setUp() {
        // Unconfined: the blocking HTTP call runs inline on the test thread, so each
        // login() has finished (and failed against the closed port) when it returns.
        service = AuthenticationService(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        threads.close()
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

    // ── cancellation mid-request ──────────────────────────────────────────────

    @Test
    fun `login cancelled mid-request does not leave the shared state loading`() = runBlocking {
        val ioService = AuthenticationService(threads)
        ScriptedHttpServer(listOf(HTTP_UNAUTHORIZED to "")).use { server ->
            val call = launch(threads) { ioService.login(server.url, "user", "pw") }
            server.awaitRequest()
            call.cancel()
            server.respond()
            call.join()
        }

        assertFalse(ioService.authState.value.isLoading, "A cancelled login must not stay loading")
    }

    @Test
    fun `logout cancelled mid-request still clears the local session`() = runBlocking {
        val ioService = AuthenticationService(threads)
        ScriptedHttpServer(listOf(HTTP_OK to """{"token":"t"}""", HTTP_OK to "")).use { server ->
            server.respond()
            ioService.login(server.url, "user", "pw")
            server.awaitRequest()
            assertTrue(ioService.authState.value.isAuthenticated)

            val call = launch(threads) { ioService.logout() }
            server.awaitRequest()
            call.cancel()
            server.respond()
            call.join()
        }

        assertFalse(ioService.authState.value.isAuthenticated, "Logout must clear the session")
        assertNull(ioService.authState.value.token)
    }

    private companion object {
        const val HTTP_OK = "HTTP/1.1 200 OK"
        const val HTTP_UNAUTHORIZED = "HTTP/1.1 401 Unauthorized"
    }
}

/**
 * Loopback HTTP server that answers its requests in order with [responses] (status line to
 * JSON body), each only once [respond] releases it — so a test can act while the client is
 * blocked inside a request.
 */
private class ScriptedHttpServer(private val responses: List<Pair<String, String>>) : AutoCloseable {
    private val socket = ServerSocket(0, responses.size, InetAddress.getLoopbackAddress())
    private val accepted = Semaphore(0)
    private val released = Semaphore(0)
    private val worker = thread(isDaemon = true) {
        responses.forEach { (statusLine, body) ->
            socket.accept().use { client ->
                // Consume the whole request first: closing a socket with unread input
                // resets the connection instead of delivering the response.
                client.getInputStream().readHttpRequest()
                accepted.release()
                released.acquire()
                val bytes = body.toByteArray()
                val head = "$statusLine\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                client.getOutputStream().write(head.toByteArray() + bytes)
                client.getOutputStream().flush()
            }
        }
    }

    val url: String = "http://127.0.0.1:${socket.localPort}"

    /** Blocks until the next request has arrived. */
    fun awaitRequest() {
        check(accepted.tryAcquire(TIMEOUT_S, TimeUnit.SECONDS)) { "no request arrived" }
    }

    /** Lets the server answer one request. */
    fun respond() = released.release()

    override fun close() {
        released.release(responses.size)
        socket.close()
        worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S))
    }

    private companion object {
        const val TIMEOUT_S = 5L
    }
}

/** Reads one request's header block and its Content-Length body. */
private fun InputStream.readHttpRequest() {
    val reader = bufferedReader()
    var contentLength = 0
    while (true) {
        val line = reader.readLine()
        if (line.isNullOrEmpty()) break
        if (line.startsWith("Content-Length:", ignoreCase = true)) {
            contentLength = line.substringAfter(':').trim().toInt()
        }
    }
    repeat(contentLength) { reader.read() }
}
