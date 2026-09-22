package com.signalk.companion.service

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Loopback stand-in for a SignalK server, used by [SignalKTransmitterTest].
 *
 * It speaks just enough HTTP to answer `/signalk/v1/auth/login`, and just enough of
 * RFC 6455 to either complete or refuse the `/signalk/v1/stream` upgrade and then read
 * the client's text frames back out. That is deliberately the whole surface: it lets the
 * transmitter be driven through its real OkHttp stack — connection state machine,
 * authentication-failure recovery and message encoding included — without mocking any
 * part of the class under test.
 *
 * Every response is scriptable, so a test can stage the exact server behaviour it needs:
 * a 401 on the upgrade, a login that fails, a login that blocks until the test releases it.
 */
internal class FakeSignalKServer : AutoCloseable {

    data class RecordedRequest(
        val method: String,
        val path: String,
        /** Header names lower-cased; HTTP header names are case-insensitive. */
        val headers: Map<String, String>,
        val body: String
    ) {
        val authorization: String? get() = headers["authorization"]
    }

    private val server = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))
    private val connections = Collections.synchronizedList(mutableListOf<Socket>())
    private val scriptedStreamStatuses = ConcurrentLinkedDeque<Int>()
    private val messages = LinkedBlockingQueue<String>()

    val port: Int get() = server.localPort
    val url: String get() = "http://$LOOPBACK:$port"

    /** Status served to upgrade requests once [scriptStreamStatuses] is exhausted. */
    @Volatile
    var defaultStreamStatus: Int = STATUS_SWITCHING_PROTOCOLS

    /** Status served by the login endpoint; anything but 200 is a failed login. */
    @Volatile
    var loginStatus: Int = 200

    /** Token handed out by a successful login. */
    @Volatile
    var loginToken: String = "token-1"

    /** When set, the login endpoint blocks until the test counts this latch down. */
    @Volatile
    var loginGate: CountDownLatch? = null

    val streamRequests = CopyOnWriteArrayList<RecordedRequest>()
    val loginRequests = CopyOnWriteArrayList<RecordedRequest>()

    init {
        thread(isDaemon = true, name = "fake-signalk-accept") { acceptLoop() }
    }

    /**
     * Stages the HTTP status for the next upgrade request(s), oldest first.
     * [STATUS_SWITCHING_PROTOCOLS] accepts the upgrade; anything else refuses it and
     * surfaces at the client as `onFailure` carrying that response.
     */
    fun scriptStreamStatuses(vararg statuses: Int) {
        statuses.forEach { scriptedStreamStatuses.addLast(it) }
    }

    /** Next text frame received from the client, or null if none arrived in time. */
    fun awaitMessage(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? =
        messages.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        runCatching { server.close() }
        synchronized(connections) { connections.toList() }.forEach { runCatching { it.close() } }
    }

    // ── Connection handling ───────────────────────────────────────────────────

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return // server closed
            }
            connections.add(socket)
            thread(isDaemon = true, name = "fake-signalk-connection") { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val request = readRequest(input) ?: return

            when {
                request.path.endsWith(LOGIN_PATH) -> {
                    loginRequests.add(request)
                    loginGate?.await()
                    if (loginStatus == 200) {
                        writeHttp(output, 200, "OK", """{"token":"$loginToken"}""")
                    } else {
                        writeHttp(output, loginStatus, "Login Failed", """{"message":"denied"}""")
                    }
                    socket.close()
                }

                request.path.endsWith(STREAM_PATH) -> {
                    streamRequests.add(request)
                    when (val status = scriptedStreamStatuses.pollFirst() ?: defaultStreamStatus) {
                        STATUS_SWITCHING_PROTOCOLS -> {
                            writeHandshake(output, request.headers[WEBSOCKET_KEY_HEADER].orEmpty())
                            readFrames(input) // holds the socket open until the client closes it
                        }
                        // No response at all: the client sees a transport error with a null
                        // Response, which is how a dropped or refused connection looks.
                        STATUS_CLOSE_WITHOUT_RESPONSE -> Unit
                        else -> writeHttp(output, status, "Stream Refused", "")
                    }
                    socket.close()
                }

                else -> {
                    writeHttp(output, 404, "Not Found", "")
                    socket.close()
                }
            }
        } catch (e: IOException) {
            // Client hung up, or the server was closed mid-exchange; nothing to do.
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun readRequest(input: InputStream): RecordedRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            readFully(input, bytes)
            String(bytes, Charsets.UTF_8)
        } else {
            ""
        }

        return RecordedRequest(parts[0], parts[1], headers, body)
    }

    /**
     * Reads one CRLF-terminated line straight off the stream. A [java.io.BufferedReader]
     * would read ahead past the header block and swallow the WebSocket frames that follow.
     */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(b.toChar())
        }
    }

    private fun writeHttp(output: OutputStream, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    /**
     * Completes the RFC 6455 opening handshake. The accept key is mandatory: OkHttp
     * verifies it and fails the connection when it does not match.
     *
     * `permessage-deflate` is deliberately not echoed back, so the client sends
     * uncompressed frames this server can read.
     */
    private fun writeHandshake(output: OutputStream, clientKey: String) {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((clientKey + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII))
        )
        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n\r\n")
        }
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    // ── WebSocket frames ──────────────────────────────────────────────────────

    private fun readFrames(input: InputStream) {
        while (true) {
            val first = input.read()
            if (first == -1) return
            val opcode = first and 0x0F

            val second = input.read()
            if (second == -1) return
            val masked = (second and 0x80) != 0

            var length = (second and 0x7F).toLong()
            when (length) {
                126L -> {
                    val extended = ByteArray(2)
                    readFully(input, extended)
                    length = ((extended[0].toLong() and 0xFF) shl 8) or (extended[1].toLong() and 0xFF)
                }

                127L -> {
                    val extended = ByteArray(8)
                    readFully(input, extended)
                    length = extended.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                }
            }

            val mask = ByteArray(4)
            if (masked) readFully(input, mask)

            val payload = ByteArray(length.toInt())
            readFully(input, payload)
            if (masked) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }

            when (opcode) {
                OPCODE_TEXT -> messages.put(String(payload, Charsets.UTF_8))
                OPCODE_CLOSE -> return
                else -> Unit // continuation/binary/ping/pong are not exercised
            }
        }
    }

    private fun readFully(input: InputStream, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val read = input.read(destination, offset, destination.size - offset)
            if (read == -1) throw IOException("Unexpected end of stream")
            offset += read
        }
    }

    companion object {
        const val STATUS_SWITCHING_PROTOCOLS = 101

        /** Pseudo-status: close the connection without writing any response at all. */
        const val STATUS_CLOSE_WITHOUT_RESPONSE = 0

        const val DEFAULT_TIMEOUT_MS = 10_000L

        private const val LOOPBACK = "127.0.0.1"
        private const val LOGIN_PATH = "/signalk/v1/auth/login"
        private const val STREAM_PATH = "/signalk/v1/stream"
        private const val WEBSOCKET_KEY_HEADER = "sec-websocket-key"
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
    }
}
