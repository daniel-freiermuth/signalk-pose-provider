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

    // Calibration angles are ZXZ (α, β, γ), the parameterisation the app moved to in
    // "feat: replace UI from Tait-Bryan to Euler decomposition". These tests still asserted
    // the superseded ZYX form — keys `calibration_rz/ry/rx_deg`, and a symmetric pitch bound
    // that made β = 91° illegal — so they had been failing against the shipped code ever
    // since that migration. Updated to the current parameterisation with their intent
    // unchanged: out-of-range is rejected, valid values are stored under the documented keys.

    @Test
    fun testSetCalibrationAngles_rejectsAlphaOutOfRange() {
        assertThrows<IllegalArgumentException> {
            AppSettings.setCalibrationAngles(mockContext, 181f, 0f, 0f)
        }
    }

    @Test
    fun testSetCalibrationAngles_rejectsBetaAboveRange() {
        assertThrows<IllegalArgumentException> {
            AppSettings.setCalibrationAngles(mockContext, 0f, 181f, 0f)
        }
    }

    @Test
    fun testSetCalibrationAngles_rejectsBetaBelowRange() {
        // β is tilt from horizontal, defined on [0°, 180°]. Unlike the symmetric ZYX pitch
        // it replaced, a negative β is invalid rather than merely unusual — a case the old
        // test could not express, so it is added here rather than translated.
        assertThrows<IllegalArgumentException> {
            AppSettings.setCalibrationAngles(mockContext, 0f, -1f, 0f)
        }
    }

    @Test
    fun testSetCalibrationAngles_rejectsGammaOutOfRange() {
        assertThrows<IllegalArgumentException> {
            AppSettings.setCalibrationAngles(mockContext, 0f, 0f, -181f)
        }
    }

    @Test
    fun testSetCalibrationAngles_acceptsValidValues() {
        AppSettings.setCalibrationAngles(mockContext, 90f, 45f, -30f)
        verify(mockEditor).putFloat(eq("calibration_alpha_deg"), eq(90f))
        verify(mockEditor).putFloat(eq("calibration_beta_deg"), eq(45f))
        verify(mockEditor).putFloat(eq("calibration_gamma_deg"), eq(-30f))
    }

    @Test
    fun testSetCalibrationAngles_acceptsBoundaryValues() {
        AppSettings.setCalibrationAngles(mockContext, 180f, 180f, -180f)
        verify(mockEditor).putFloat(eq("calibration_alpha_deg"), eq(180f))
        verify(mockEditor).putFloat(eq("calibration_beta_deg"), eq(180f))
        verify(mockEditor).putFloat(eq("calibration_gamma_deg"), eq(-180f))
    }

    @Test
    fun testSetCalibrationAngles_acceptsBetaAtZero() {
        // β = 0 is the flat-mount gimbal-lock case DeviceCalibration documents; it is a
        // legal stored value even though the decomposition is degenerate there.
        AppSettings.setCalibrationAngles(mockContext, 0f, 0f, 0f)
        verify(mockEditor).putFloat(eq("calibration_beta_deg"), eq(0f))
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
