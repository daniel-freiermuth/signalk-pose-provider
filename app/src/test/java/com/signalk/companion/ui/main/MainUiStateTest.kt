package com.signalk.companion.ui.main

import com.signalk.companion.util.UrlParser
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MainUiStateTest {

    @Test
    fun testHostnameParsingWithTrailingPath() {
        val url = "http://192.168.1.1/signalk"
        val uiState = MainUiState(parsedUrl = UrlParser.parseUrl(url))
        
        assertEquals("Hostname should be extracted correctly from URL with path", 
            "192.168.1.1", uiState.parsedUrl?.hostname)
        assertEquals(80, uiState.parsedUrl?.port)
        assertEquals(false, uiState.parsedUrl?.isHttps)
        assertEquals("http://192.168.1.1", uiState.serverUrl)
    }

    @Test
    fun testHostnameParsingWithoutProtocol() {
        val url = "192.168.1.1/signalk"
        val uiState = MainUiState(parsedUrl = UrlParser.parseUrl(url))
        
        assertEquals("Hostname should be extracted correctly from URL without protocol but with path", 
            "192.168.1.1", uiState.parsedUrl?.hostname)
        assertEquals(80, uiState.parsedUrl?.port)
        assertEquals("http://192.168.1.1", uiState.serverUrl)
    }

    @Test
    fun testHostnameParsingWithPort() {
        val url = "http://192.168.1.1:3000"
        val uiState = MainUiState(parsedUrl = UrlParser.parseUrl(url))
        
        assertEquals("192.168.1.1", uiState.parsedUrl?.hostname)
        assertEquals(3000, uiState.parsedUrl?.port)
        assertEquals(false, uiState.parsedUrl?.isHttps)
        assertEquals("http://192.168.1.1:3000", uiState.serverUrl)
    }

    @Test
    fun testHostnameParsingWithPortAndPath() {
        val url = "http://192.168.1.1:3000/signalk/v1"
        val uiState = MainUiState(parsedUrl = UrlParser.parseUrl(url))
        
        assertEquals("192.168.1.1", uiState.parsedUrl?.hostname)
        assertEquals(3000, uiState.parsedUrl?.port)
        assertEquals(false, uiState.parsedUrl?.isHttps)
        assertEquals("http://192.168.1.1:3000", uiState.serverUrl)
    }

    @Test
    fun testHostnameParsingWithHttps() {
        val url = "https://signalk.local:3443"
        val uiState = MainUiState(parsedUrl = UrlParser.parseUrl(url))
        
        assertEquals("signalk.local", uiState.parsedUrl?.hostname)
        assertEquals(3443, uiState.parsedUrl?.port)
        assertEquals(true, uiState.parsedUrl?.isHttps)
        assertEquals("https://signalk.local:3443", uiState.serverUrl)
    }

    @Test
    fun testHostnameParsingWithHttpsNoPort() {
        val url = "https://signalk.local"
        val uiState = MainUiState(parsedUrl = UrlParser.parseUrl(url))
        
        assertEquals("signalk.local", uiState.parsedUrl?.hostname)
        assertEquals(443, uiState.parsedUrl?.port)
        assertEquals(true, uiState.parsedUrl?.isHttps)
        assertEquals("https://signalk.local", uiState.serverUrl)
    }

    @Test
    fun testInvalidUrlReturnsNullValues() {
        val url = "ftp://invalid.server"
        val uiState = MainUiState(parsedUrl = UrlParser.parseUrl(url))
        
        assertNull("ParsedUrl should be null for invalid URL", uiState.parsedUrl)
        assertEquals("", uiState.serverUrl)
    }
    
    @Test
    fun testEmptyUrlReturnsNullValues() {
        val url = ""
        val uiState = MainUiState(parsedUrl = UrlParser.parseUrl(url))
        
        assertNull(uiState.parsedUrl)
        assertEquals("", uiState.serverUrl)
    }
    
    @Test
    fun testValidUrlHasValidFlag() {
        val url = "http://192.168.1.1:3000"
        val uiState = MainUiState(parsedUrl = UrlParser.parseUrl(url))
        
        assertTrue("Should have valid parsed URL", uiState.parsedUrl != null)
        assertEquals("http://192.168.1.1:3000", uiState.serverUrl)
    }
}
