package com.signalk.companion.util

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.net.URI

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
                port == 80 && !isHttps -> ""
                port == 443 && isHttps -> ""
                else -> ":$port"
            }
            return "$protocol://$hostname$portPart"
        }
    }
    
    private val ALLOWED_SCHEMES = setOf("http", "https", "ws", "wss")
    
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
        return try {
            // Check if URL already has a scheme
            // Don't add http:// if there's already any scheme (even if not ://)
            val hasScheme = url.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*"))
            val urlWithScheme = if (hasScheme) url else "http://$url"
            
            // Parse using standard URI parser
            val uri = URI(urlWithScheme)
            
            // Reject opaque URIs (like mailto:, urn:, tel:, etc.)
            // We only accept hierarchical URIs (with ://)
            if (uri.isOpaque) {
                return null
            }
            
            // Validate scheme
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme !in ALLOWED_SCHEMES) {
                return null
            }
            
            // Extract hostname
            val hostname = uri.host ?: return null
            if (hostname.isEmpty()) {
                return null
            }
            
            // Determine if secure
            val isHttps = scheme in setOf("https", "wss")
            
            // Extract port or use default
            val port = if (uri.port != -1) {
                uri.port
            } else {
                if (isHttps) 443 else 80
            }
            
            // Check if URL contains a path (will be ignored for SignalK connection)
            val path = uri.path ?: ""
            val hasPath = path.isNotEmpty() && path != "/"
            
            ParsedUrl(hostname, port, isHttps, hasPath)
        } catch (e: Exception) {
            null
        }
    }
}
