package com.signalk.companion.util

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREF_NAME = "signalk_companion_settings"
    
    // Setting keys
    private const val KEY_VESSEL_ID = "vessel_id"
    
    // Default values
    private const val DEFAULT_VESSEL_ID = "self"
    
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
}
