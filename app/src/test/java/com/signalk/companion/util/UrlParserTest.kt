package com.signalk.companion.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

class UrlParserTest {

    @Test
    fun testParseUrlWithProtocolAndPort() {
        val url = "http://192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithHttpsAndPort() {
        val url = "https://192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(true, result?.isHttps)
    }

    @Test
    fun testParseUrlWithProtocolNoPort() {
        val url = "http://192.168.1.1"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithHttpsNoPort() {
        val url = "https://192.168.1.1"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(443, result?.port)
        assertEquals(true, result?.isHttps)
    }

    @Test
    fun testParseUrlWithPathNoPort() {
        // This is the main bug being fixed
        val url = "http://192.168.1.1/signalk"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname, "Hostname should not include path")
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithPortAndPath() {
        val url = "http://192.168.1.1:3000/signalk"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithHttpsPortAndPath() {
        val url = "https://signalk.local:3443/signalk/v1/stream"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("signalk.local", result?.hostname)
        assertEquals(3443, result?.port)
        assertEquals(true, result?.isHttps)
    }

    @Test
    fun testParseUrlWithoutProtocol() {
        val url = "192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithoutProtocolNoPort() {
        val url = "192.168.1.1"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithoutProtocolButWithPath() {
        // Bug report test case
        val url = "192.168.1.1/signalk"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname,
            "Hostname should be extracted correctly from URL without protocol but with path")
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithHostname() {
        val url = "http://signalk.local"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("signalk.local", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithHostnameAndPath() {
        val url = "http://my-boat.local/signalk/v1"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("my-boat.local", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithWebSocketProtocol() {
        val url = "ws://192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithSecureWebSocketProtocol() {
        val url = "wss://192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(true, result?.isHttps)
    }

    @Test
    fun testParseUrlWithWssAndPath() {
        val url = "wss://signalk.local:3443/signalk/v1/stream"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("signalk.local", result?.hostname)
        assertEquals(3443, result?.port)
        assertEquals(true, result?.isHttps)
    }

    @Test
    fun testParseUrlHandlesWhitespace() {
        val url = "http://192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
    }

    @Test
    fun testParseUrlWithComplexPath() {
        val url = "http://192.168.1.1/signalk/v1/api/vessels/self"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull(result, "URL should parse successfully")
        assertEquals("192.168.1.1", result?.hostname, "Hostname should not include complex path")
        assertEquals(80, result?.port)
    }
    
    @Test
    fun testParseUrlReturnsNullForInvalidUrl() {
        // Test that completely invalid input returns null
        val url = ""
        val result = UrlParser.parseUrl(url)
        
        assertNull(result, "Empty URL should return null")
    }
    
    @Test
    fun testParseUrlRejectsInvalidProtocol() {
        val urls = listOf(
            "ftp://192.168.1.1",
            "file:///path/to/file",
            "ssh://server.com"
        )
        
        urls.forEach { url ->
            val result = UrlParser.parseUrl(url)
            assertNull(result, "URL with invalid protocol should return null: $url")
        }
    }
    
    @Test
    fun testParseUrlHandlesMalformedUrls() {
        val urls = listOf(
            "http://",
            "://192.168.1.1",
            "not a url at all"
        )
        
        urls.forEach { url ->
            val result = UrlParser.parseUrl(url)
            assertNull(result, "Malformed URL should return null: $url")
        }
    }
    
    @Test
    fun testToUrlStringOmitsDefaultHttpPort() {
        val parsed = UrlParser.ParsedUrl("192.168.1.1", 80, false)
        assertEquals("http://192.168.1.1", parsed.toUrlString())
    }
    
    @Test
    fun testToUrlStringOmitsDefaultHttpsPort() {
        val parsed = UrlParser.ParsedUrl("signalk.local", 443, true)
        assertEquals("https://signalk.local", parsed.toUrlString())
    }
    
    @Test
    fun testToUrlStringIncludesNonDefaultHttpPort() {
        val parsed = UrlParser.ParsedUrl("192.168.1.1", 3000, false)
        assertEquals("http://192.168.1.1:3000", parsed.toUrlString())
    }
    
    @Test
    fun testToUrlStringIncludesNonDefaultHttpsPort() {
        val parsed = UrlParser.ParsedUrl("signalk.local", 3443, true)
        assertEquals("https://signalk.local:3443", parsed.toUrlString())
    }
    
    @Test
    fun testToUrlStringRoundTrip() {
        val urls = listOf(
            "http://192.168.1.1",
            "http://192.168.1.1:3000",
            "https://signalk.local",
            "https://signalk.local:3443"
        )
        
        urls.forEach { url ->
            val parsed = UrlParser.parseUrl(url)
            assertNotNull(parsed, "URL should parse: $url")
            assertEquals(url, parsed?.toUrlString(), "Round trip should preserve URL: $url")
        }
    }
    
    @Test
    fun testPathDetectionWithPath() {
        val urlsWithPath = listOf(
            "http://192.168.1.1/signalk",
            "http://192.168.1.1:3000/signalk/v1/stream",
            "https://signalk.local/api",
            "https://signalk.local/custom/path"
        )
        
        urlsWithPath.forEach { url ->
            val parsed = UrlParser.parseUrl(url)
            assertNotNull(parsed, "URL should parse: $url")
            assertTrue(parsed?.hasPath ?: false, "URL should have path flag set: $url")
        }
    }
    
    @Test
    fun testPathDetectionWithoutPath() {
        val urlsWithoutPath = listOf(
            "http://192.168.1.1",
            "http://192.168.1.1:3000",
            "https://signalk.local",
            "http://192.168.1.1/",  // Root path doesn't count
            "192.168.1.1:3000"
        )
        
        urlsWithoutPath.forEach { url ->
            val parsed = UrlParser.parseUrl(url)
            assertNotNull(parsed, "URL should parse: $url")
            assertFalse(parsed?.hasPath ?: true, "URL should not have path flag set: $url")
        }
    }

    @Test
    fun testParseUrlWithLocalhost() {
        val result = UrlParser.parseUrl("localhost")
        assertNotNull(result, "localhost should parse successfully")
        assertEquals("localhost", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithLocalhostAndPort() {
        val result = UrlParser.parseUrl("localhost:3000")
        assertNotNull(result, "localhost:3000 should parse successfully")
        assertEquals("localhost", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithLocalhostScheme() {
        val result = UrlParser.parseUrl("http://localhost:3000")
        assertNotNull(result, "http://localhost:3000 should parse successfully")
        assertEquals("localhost", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testToUrlStringLocalhostRoundTrip() {
        val urls = listOf("localhost", "localhost:3000", "http://localhost", "http://localhost:3000")
        urls.forEach { url ->
            val parsed = UrlParser.parseUrl(url)
            assertNotNull(parsed, "localhost URL should parse: $url")
            // toUrlString() must produce an absolute URL (with scheme) so it can be used in URL()
            assertTrue(
                parsed!!.toUrlString().startsWith("http://") || parsed.toUrlString().startsWith("https://"),
                "toUrlString() must start with http:// or https:// for: $url"
            )
        }
    }
}