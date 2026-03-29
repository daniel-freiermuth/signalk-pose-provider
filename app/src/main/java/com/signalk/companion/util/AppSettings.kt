package com.signalk.companion.util

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREF_NAME = "signalk_companion_settings"
    
    // Setting keys
    private const val KEY_VESSEL_ID = "vessel_id"
    private const val KEY_SEND_LOCATION = "send_location"
    private const val KEY_SEND_HEADING = "send_heading"
    private const val KEY_SEND_PRESSURE = "send_pressure"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_CALIBRATION_RZ = "calibration_rz_deg"
    private const val KEY_CALIBRATION_RY = "calibration_ry_deg"
    private const val KEY_CALIBRATION_RX = "calibration_rx_deg"
    private const val KEY_LOCATION_INTERVAL_MS = "location_interval_ms"
    private const val KEY_SENSOR_INTERVAL_MS = "sensor_interval_ms"
    private const val KEY_WAS_STREAMING = "was_streaming"
    
    // Default values
    private const val DEFAULT_VESSEL_ID = "self"
    private const val DEFAULT_SEND_LOCATION = true
    private const val DEFAULT_SEND_HEADING = true
    private const val DEFAULT_SEND_PRESSURE = true
    private const val DEFAULT_SERVER_URL = ""
    private const val DEFAULT_CALIBRATION_ANGLE = 0.0f
    private const val DEFAULT_LOCATION_INTERVAL_MS = 1000L
    private const val DEFAULT_SENSOR_INTERVAL_MS = 250L
    
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Get the vessel ID for SignalK context
     * @return vessel ID string (defaults to "self")
     */
    fun getVesselId(context: Context): String {
        return getPreferences(context).getString(KEY_VESSEL_ID, DEFAULT_VESSEL_ID) ?: DEFAULT_VESSEL_ID
    }
    
    /**
     * Set the vessel ID for SignalK context
     * @param vesselId the vessel ID to use (e.g., "self", "urn:mrn:imo:imo-number:1234567", etc.)
     */
    fun setVesselId(context: Context, vesselId: String) {
        val trimmedId = vesselId.trim()
        // Prevent saving blank vessel ID which would create invalid context "vessels."
        val validId = if (trimmedId.isBlank()) DEFAULT_VESSEL_ID else trimmedId
        getPreferences(context).edit()
            .putString(KEY_VESSEL_ID, validId)
            .apply()
    }
    
    /**
     * Get the full SignalK context string
     * @return full context string like "vessels.self" or "vessels.urn:mrn:imo:imo-number:1234567"
     */
    fun getSignalKContext(context: Context): String {
        val vesselId = getVesselId(context)
        // If already a full context path, return as-is to prevent double-prefixing
        if (vesselId.startsWith("vessels.") ||
            vesselId.startsWith("aircraft.") ||
            vesselId.startsWith("aton.") ||
            vesselId.startsWith("shore.")) {
            return vesselId
        }
        return "vessels.$vesselId"
    }
    
    // Data transmission settings
    
    /**
     * Get whether to send location data
     */
    fun getSendLocation(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SEND_LOCATION, DEFAULT_SEND_LOCATION)
    }
    
    /**
     * Set whether to send location data
     */
    fun setSendLocation(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SEND_LOCATION, enabled)
            .apply()
    }
    
    /**
     * Get whether to send heading data
     */
    fun getSendHeading(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SEND_HEADING, DEFAULT_SEND_HEADING)
    }
    
    /**
     * Set whether to send heading data
     */
    fun setSendHeading(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SEND_HEADING, enabled)
            .apply()
    }
    
    /**
     * Get whether to send pressure data
     */
    fun getSendPressure(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SEND_PRESSURE, DEFAULT_SEND_PRESSURE)
    }
    
    /**
     * Set whether to send pressure data
     */
    fun setSendPressure(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SEND_PRESSURE, enabled)
            .apply()
    }
    
    // Server URL settings
    
    /**
     * Get the SignalK server URL
     */
    fun getServerUrl(context: Context): String {
        return getPreferences(context).getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }
    
    /**
     * Set the SignalK server URL
     */
    fun setServerUrl(context: Context, url: String) {
        getPreferences(context).edit()
            .putString(KEY_SERVER_URL, url.trim())
            .apply()
    }
    
    // Authentication credentials
    
    /**
     * Get the stored username
     */
    fun getUsername(context: Context): String {
        return getPreferences(context).getString(KEY_USERNAME, "") ?: ""
    }
    
    /**
     * Set the username
     */
    fun setUsername(context: Context, username: String) {
        getPreferences(context).edit()
            .putString(KEY_USERNAME, username.trim())
            .apply()
    }
    
    /**
     * Get the stored password (Note: stored in plain text, consider encryption for production)
     */
    fun getPassword(context: Context): String {
        return getPreferences(context).getString(KEY_PASSWORD, "") ?: ""
    }
    
    /**
     * Set the password
     */
    fun setPassword(context: Context, password: String) {
        getPreferences(context).edit()
            .putString(KEY_PASSWORD, password)
            .apply()
    }
    
    /**
     * Clear all credentials
     */
    fun clearCredentials(context: Context) {
        getPreferences(context).edit()
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .apply()
    }
    
    /**
     * Check if credentials are stored
     */
    fun hasCredentials(context: Context): Boolean {
        val username = getUsername(context)
        val password = getPassword(context)
        return username.isNotBlank() && password.isNotBlank()
    }
    
    // Device-to-vehicle calibration angles (ZYX Euler decomposition)

    fun getCalibrationRzDeg(context: Context): Float {
        return getPreferences(context).getFloat(KEY_CALIBRATION_RZ, DEFAULT_CALIBRATION_ANGLE)
    }

    fun getCalibrationRyDeg(context: Context): Float {
        return getPreferences(context).getFloat(KEY_CALIBRATION_RY, DEFAULT_CALIBRATION_ANGLE)
    }

    fun getCalibrationRxDeg(context: Context): Float {
        return getPreferences(context).getFloat(KEY_CALIBRATION_RX, DEFAULT_CALIBRATION_ANGLE)
    }

    fun setCalibrationAngles(context: Context, rzDeg: Float, ryDeg: Float, rxDeg: Float) {
        require(rzDeg in -180f..180f) { "RZ must be between -180 and 180 degrees" }
        require(ryDeg in -90f..90f) { "RY must be between -90 and 90 degrees" }
        require(rxDeg in -180f..180f) { "RX must be between -180 and 180 degrees" }
        getPreferences(context).edit()
            .putFloat(KEY_CALIBRATION_RZ, rzDeg)
            .putFloat(KEY_CALIBRATION_RY, ryDeg)
            .putFloat(KEY_CALIBRATION_RX, rxDeg)
            .apply()
    }

    fun getLocationIntervalMs(context: Context): Long {
        return getPreferences(context).getLong(KEY_LOCATION_INTERVAL_MS, DEFAULT_LOCATION_INTERVAL_MS)
    }

    fun setLocationIntervalMs(context: Context, intervalMs: Long) {
        require(intervalMs > 0) { "Location interval must be positive" }
        getPreferences(context).edit()
            .putLong(KEY_LOCATION_INTERVAL_MS, intervalMs)
            .apply()
    }

    fun getSensorIntervalMs(context: Context): Long {
        return getPreferences(context).getLong(KEY_SENSOR_INTERVAL_MS, DEFAULT_SENSOR_INTERVAL_MS)
    }

    fun setSensorIntervalMs(context: Context, intervalMs: Long) {
        require(intervalMs > 0) { "Sensor interval must be positive" }
        getPreferences(context).edit()
            .putLong(KEY_SENSOR_INTERVAL_MS, intervalMs)
            .apply()
    }

    /** Persists whether the service was actively streaming. Used to resume after an OS kill. */
    fun setWasStreaming(context: Context, streaming: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_WAS_STREAMING, streaming)
            .apply()
    }

    fun getWasStreaming(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_WAS_STREAMING, false)
    }
}
