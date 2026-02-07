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
    
    // Default values
    private const val DEFAULT_VESSEL_ID = "self"
    private const val DEFAULT_SEND_LOCATION = true
    private const val DEFAULT_SEND_HEADING = true
    private const val DEFAULT_SEND_PRESSURE = true
    private const val DEFAULT_SERVER_URL = ""
    
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
        getPreferences(context).edit()
            .putString(KEY_VESSEL_ID, vesselId.trim())
            .apply()
    }
    
    /**
     * Get the full SignalK context string
     * @return full context string like "vessels.self" or "vessels.urn:mrn:imo:imo-number:1234567"
     */
    fun getSignalKContext(context: Context): String {
        val vesselId = getVesselId(context)
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
}
