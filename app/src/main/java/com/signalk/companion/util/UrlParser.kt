package com.signalk.companion.util

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.net.URI
import java.net.URISyntaxException

/**
 * Utility class for parsing SignalK server URLs.
 * Handles URLs with or without protocol, port, and path components.
 */
object UrlParser {

    @Parcelize
    data class ParsedUrl(
        val hostname: String,
        val port: Int,
        val isHttps: Boolean,
        val hasPath: Boolean = false
    ) : Parcelable {
        /**
         * Reconstructs a normalized URL string from parsed components.
         * Omits the port when it's the default (80 for HTTP, 443 for HTTPS).
         */
        fun toUrlString(): String {
            val protocol = if (isHttps) "https" else "http"
            val portPart = when {
                port == HTTP_DEFAULT_PORT && !isHttps -> ""
                port == HTTPS_DEFAULT_PORT && isHttps -> ""
                else -> ":$port"
            }
            return "$protocol://$hostname$portPart"
        }
    }

    private const val HTTP_DEFAULT_PORT = 80
    private const val HTTPS_DEFAULT_PORT = 443

    private val ALLOWED_SCHEMES = setOf("http", "https", "ws", "wss")
    private val SECURE_SCHEMES = setOf("https", "wss")

    /** Matches a hierarchical scheme prefix such as `http://` at the start of the input. */
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")

    /**
     * Parses a URL string and extracts hostname, port, and protocol information.
     *
     * Handles various URL formats:
     * - http://192.168.1.1
     * - https://192.168.1.1:3000
     * - http://192.168.1.1/signalk
     * - http://192.168.1.1:3000/signalk
     * - 192.168.1.1 (defaults to HTTP)
     * - signalk.local:3000/api
     * - ws://192.168.1.1:3000 (WebSocket)
     * - wss://192.168.1.1:3000 (Secure WebSocket)
     *
     * Uses standard Java URI parser for robust URL validation.
     * Rejects non-HTTP/WebSocket protocols (e.g., ftp://, file://, mailto:).
     *
     * @param url The URL string to parse
     * @return ParsedUrl containing hostname, port, and HTTPS flag, or null if parsing fails
     */
    fun parseUrl(url: String): ParsedUrl? {
        // Check if URL already has a scheme (must have "://" to be a hierarchical scheme)
        // Using just ":" would incorrectly treat "localhost:3000" as having scheme "localhost"
        val urlWithScheme = if (url.matches(SCHEME_PREFIX)) url else "http://$url"
        val uri = parseUri(urlWithScheme) ?: return null

        val scheme = uri.scheme?.lowercase()
        val hostname = uri.host
        // Reject opaque URIs (like mailto:, urn:, tel:, etc.) - we only accept hierarchical
        // URIs (with ://) - as well as unsupported schemes and URIs without a host.
        return if (uri.isOpaque || scheme !in ALLOWED_SCHEMES || hostname.isNullOrEmpty()) {
            null
        } else {
            toParsedUrl(uri, hostname, isHttps = scheme in SECURE_SCHEMES)
        }
    }

    /**
     * Parse with the standard URI parser; null when the input is not a syntactically valid
     * URI. Malformed user input is an expected outcome here, and [parseUrl]'s contract is to
     * answer it with null, so the exception carries nothing the caller needs.
     */
    private fun parseUri(urlWithScheme: String): URI? =
        try {
            URI(urlWithScheme)
        } catch (expected: URISyntaxException) {
            null
        }

    private fun toParsedUrl(uri: URI, hostname: String, isHttps: Boolean): ParsedUrl {
        // Extract port or use default
        val port = when {
            uri.port != -1 -> uri.port
            isHttps -> HTTPS_DEFAULT_PORT
            else -> HTTP_DEFAULT_PORT
        }

        // Check if URL contains a path (will be ignored for SignalK connection)
        val path = uri.path.orEmpty()
        val hasPath = path.isNotEmpty() && path != "/"

        return ParsedUrl(hostname, port, isHttps, hasPath)
    }
}
