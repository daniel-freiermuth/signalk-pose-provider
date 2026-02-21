package com.signalk.companion.util

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class UrlParserTest {

    @Test
    fun testParseUrlWithProtocolAndPort() {
        val url = "http://192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithHttpsAndPort() {
        val url = "https://192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(true, result?.isHttps)
    }

    @Test
    fun testParseUrlWithProtocolNoPort() {
        val url = "http://192.168.1.1"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithHttpsNoPort() {
        val url = "https://192.168.1.1"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(443, result?.port)
        assertEquals(true, result?.isHttps)
    }

    @Test
    fun testParseUrlWithPathNoPort() {
        // This is the main bug being fixed
        val url = "http://192.168.1.1/signalk"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("Hostname should not include path", "192.168.1.1", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithPortAndPath() {
        val url = "http://192.168.1.1:3000/signalk"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithHttpsPortAndPath() {
        val url = "https://signalk.local:3443/signalk/v1/stream"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("signalk.local", result?.hostname)
        assertEquals(3443, result?.port)
        assertEquals(true, result?.isHttps)
    }

    @Test
    fun testParseUrlWithoutProtocol() {
        val url = "192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithoutProtocolNoPort() {
        val url = "192.168.1.1"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithoutProtocolButWithPath() {
        // Bug report test case
        val url = "192.168.1.1/signalk"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("Hostname should be extracted correctly from URL without protocol but with path", 
            "192.168.1.1", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithHostname() {
        val url = "http://signalk.local"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("signalk.local", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithHostnameAndPath() {
        val url = "http://my-boat.local/signalk/v1"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("my-boat.local", result?.hostname)
        assertEquals(80, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithWebSocketProtocol() {
        val url = "ws://192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(false, result?.isHttps)
    }

    @Test
    fun testParseUrlWithSecureWebSocketProtocol() {
        val url = "wss://192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
        assertEquals(true, result?.isHttps)
    }

    @Test
    fun testParseUrlWithWssAndPath() {
        val url = "wss://signalk.local:3443/signalk/v1/stream"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("signalk.local", result?.hostname)
        assertEquals(3443, result?.port)
        assertEquals(true, result?.isHttps)
    }

    @Test
    fun testParseUrlHandlesWhitespace() {
        val url = "http://192.168.1.1:3000"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("192.168.1.1", result?.hostname)
        assertEquals(3000, result?.port)
    }

    @Test
    fun testParseUrlWithComplexPath() {
        val url = "http://192.168.1.1/signalk/v1/api/vessels/self"
        val result = UrlParser.parseUrl(url)
        
        assertNotNull("URL should parse successfully", result)
        assertEquals("Hostname should not include complex path", "192.168.1.1", result?.hostname)
        assertEquals(80, result?.port)
    }
    
    @Test
    fun testParseUrlReturnsNullForInvalidUrl() {
        // Test that completely invalid input returns null
        val url = ""
        val result = UrlParser.parseUrl(url)
        
        assertNull("Empty URL should return null", result)
    }
    
    @Test
    fun testParseUrlRejectsInvalidProtocol() {
        val urls = listOf(
            "ftp://192.168.1.1",
            "file:///path/to/file",
            "ssh://server.com",
            "mailto:test@example.com"
        )
        
        urls.forEach { url ->
            val result = UrlParser.parseUrl(url)
            assertNull("URL with invalid protocol should return null: $url", result)
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
            assertNull("Malformed URL should return null: $url", result)
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
            assertNotNull("URL should parse: $url", parsed)
            assertEquals("Round trip should preserve URL: $url", url, parsed?.toUrlString())
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
            assertNotNull("URL should parse: $url", parsed)
            assertTrue("URL should have path flag set: $url", parsed?.hasPath ?: false)
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
            assertNotNull("URL should parse: $url", parsed)
            assertFalse("URL should not have path flag set: $url", parsed?.hasPath ?: true)
        }
    }}