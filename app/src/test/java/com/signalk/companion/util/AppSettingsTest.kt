package com.signalk.companion.util

import android.content.Context
import android.content.SharedPreferences
import org.junit.Test
import org.junit.Before
import org.mockito.Mockito.*

class AppSettingsTest {

    private lateinit var mockContext: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        mockContext = mock(Context::class.java)
        mockSharedPreferences = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)
        
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockSharedPreferences)
        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)
        `when`(mockEditor.putFloat(anyString(), anyFloat())).thenReturn(mockEditor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSetDeviceOrientation_rejectsBlankString() {
        AppSettings.setDeviceOrientation(mockContext, "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSetDeviceOrientation_rejectsWhitespaceString() {
        AppSettings.setDeviceOrientation(mockContext, "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSetHeadingOffset_rejectsTooLargePositiveValue() {
        AppSettings.setHeadingOffset(mockContext, 361f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSetHeadingOffset_rejectsTooLargeNegativeValue() {
        AppSettings.setHeadingOffset(mockContext, -361f)
    }

    @Test
    fun testSetHeadingOffset_acceptsValidPositiveValue() {
        AppSettings.setHeadingOffset(mockContext, 180f)
        verify(mockEditor).putFloat(anyString(), eq(180f))
    }

    @Test
    fun testSetHeadingOffset_acceptsValidNegativeValue() {
        AppSettings.setHeadingOffset(mockContext, -180f)
        verify(mockEditor).putFloat(anyString(), eq(-180f))
    }

    @Test
    fun testSetHeadingOffset_acceptsBoundaryValues() {
        AppSettings.setHeadingOffset(mockContext, 360f)
        verify(mockEditor).putFloat(anyString(), eq(360f))
        
        AppSettings.setHeadingOffset(mockContext, -360f)
        verify(mockEditor).putFloat(anyString(), eq(-360f))
    }

    @Test
    fun testSetVesselId_convertsBlankToDefault() {
        `when`(mockSharedPreferences.getString(anyString(), anyString())).thenReturn("self")
        
        AppSettings.setVesselId(mockContext, "")
        verify(mockEditor).putString(anyString(), eq("self"))
    }

    @Test
    fun testSetVesselId_trimsWhitespace() {
        AppSettings.setVesselId(mockContext, "  test-vessel  ")
        verify(mockEditor).putString(anyString(), eq("test-vessel"))
    }
}
