package com.signalk.companion.util

import android.content.Context
import android.content.SharedPreferences
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*

class AppSettingsTest {

    private lateinit var mockContext: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @BeforeEach
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

    @Test
    fun testSetCalibrationAngles_rejectsRzOutOfRange() {
        assertThrows<IllegalArgumentException> {
            AppSettings.setCalibrationAngles(mockContext, 181f, 0f, 0f)
        }
    }

    @Test
    fun testSetCalibrationAngles_rejectsRyOutOfRange() {
        assertThrows<IllegalArgumentException> {
            AppSettings.setCalibrationAngles(mockContext, 0f, 91f, 0f)
        }
    }

    @Test
    fun testSetCalibrationAngles_rejectsRxOutOfRange() {
        assertThrows<IllegalArgumentException> {
            AppSettings.setCalibrationAngles(mockContext, 0f, 0f, -181f)
        }
    }

    @Test
    fun testSetCalibrationAngles_acceptsValidValues() {
        AppSettings.setCalibrationAngles(mockContext, 90f, 45f, -30f)
        verify(mockEditor).putFloat(eq("calibration_rz_deg"), eq(90f))
        verify(mockEditor).putFloat(eq("calibration_ry_deg"), eq(45f))
        verify(mockEditor).putFloat(eq("calibration_rx_deg"), eq(-30f))
    }

    @Test
    fun testSetCalibrationAngles_acceptsBoundaryValues() {
        AppSettings.setCalibrationAngles(mockContext, 180f, 90f, 180f)
        verify(mockEditor).putFloat(eq("calibration_rz_deg"), eq(180f))
        verify(mockEditor).putFloat(eq("calibration_ry_deg"), eq(90f))
        verify(mockEditor).putFloat(eq("calibration_rx_deg"), eq(180f))
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

    @Test
    fun testGetSignalKContext_withSimpleVesselId() {
        `when`(mockSharedPreferences.getString(anyString(), anyString())).thenReturn("self")
        val result = AppSettings.getSignalKContext(mockContext)
        assert(result == "vessels.self")
    }

    @Test
    fun testGetSignalKContext_withCustomVesselId() {
        `when`(mockSharedPreferences.getString(anyString(), anyString())).thenReturn("urn:mrn:imo:imo-number:1234567")
        val result = AppSettings.getSignalKContext(mockContext)
        assert(result == "vessels.urn:mrn:imo:imo-number:1234567")
    }

    @Test
    fun testGetSignalKContext_preventsDoublePrefixing_vessels() {
        `when`(mockSharedPreferences.getString(anyString(), anyString())).thenReturn("vessels.self")
        val result = AppSettings.getSignalKContext(mockContext)
        assert(result == "vessels.self") { "Expected 'vessels.self' but got '$result'" }
    }

    @Test
    fun testGetSignalKContext_preventsDoublePrefixing_aircraft() {
        `when`(mockSharedPreferences.getString(anyString(), anyString())).thenReturn("aircraft.123")
        val result = AppSettings.getSignalKContext(mockContext)
        assert(result == "aircraft.123") { "Expected 'aircraft.123' but got '$result'" }
    }

    @Test
    fun testGetSignalKContext_preventsDoublePrefixing_aton() {
        `when`(mockSharedPreferences.getString(anyString(), anyString())).thenReturn("aton.buoy-1")
        val result = AppSettings.getSignalKContext(mockContext)
        assert(result == "aton.buoy-1") { "Expected 'aton.buoy-1' but got '$result'" }
    }

    @Test
    fun testGetSignalKContext_preventsDoublePrefixing_shore() {
        `when`(mockSharedPreferences.getString(anyString(), anyString())).thenReturn("shore.station-1")
        val result = AppSettings.getSignalKContext(mockContext)
        assert(result == "shore.station-1") { "Expected 'shore.station-1' but got '$result'" }
    }

    @Test
    fun testGetSignalKContext_allowsDotsInCustomVesselId() {
        `when`(mockSharedPreferences.getString(anyString(), anyString())).thenReturn("my.boat")
        val result = AppSettings.getSignalKContext(mockContext)
        assert(result == "vessels.my.boat") { "Expected 'vessels.my.boat' but got '$result'" }
    }
}
