package com.signalk.companion.util

import android.content.Context
import androidx.core.content.edit

/** Which vessel this device reports for, as a SignalK context. */
object VesselSettings {
    private const val KEY_VESSEL_ID = "vessel_id"
    private const val DEFAULT_VESSEL_ID = "self"

    /** Top-level SignalK context groups; an id already starting with one is a full context. */
    private val CONTEXT_PREFIXES = listOf("vessels.", "aircraft.", "aton.", "shore.")

    /**
     * Get the vessel ID for SignalK context
     * @return vessel ID string (defaults to "self")
     */
    fun getVesselId(context: Context): String =
        settingsPreferences(context).getString(KEY_VESSEL_ID, DEFAULT_VESSEL_ID) ?: DEFAULT_VESSEL_ID

    /**
     * Set the vessel ID for SignalK context
     * @param vesselId the vessel ID to use (e.g., "self", "urn:mrn:imo:imo-number:1234567", etc.)
     */
    fun setVesselId(context: Context, vesselId: String) {
        val trimmedId = vesselId.trim()
        // Prevent saving blank vessel ID which would create invalid context "vessels."
        val validId = if (trimmedId.isBlank()) DEFAULT_VESSEL_ID else trimmedId
        settingsPreferences(context).edit { putString(KEY_VESSEL_ID, validId) }
    }

    /**
     * Get the full SignalK context string
     * @return full context string like "vessels.self" or "vessels.urn:mrn:imo:imo-number:1234567"
     */
    fun getSignalKContext(context: Context): String {
        val vesselId = getVesselId(context)
        // If already a full context path, return as-is to prevent double-prefixing
        return if (CONTEXT_PREFIXES.any { vesselId.startsWith(it) }) vesselId else "vessels.$vesselId"
    }
}
