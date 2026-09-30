package com.signalk.companion.util

import android.content.Context
import androidx.core.content.edit

/** Which data groups are sent to the server. */
object TransmissionSettings {
    private const val KEY_SEND_LOCATION = "send_location"
    private const val KEY_SEND_HEADING = "send_heading"
    private const val KEY_SEND_PRESSURE = "send_pressure"
    private const val DEFAULT_SEND_LOCATION = true
    private const val DEFAULT_SEND_HEADING = true
    private const val DEFAULT_SEND_PRESSURE = true

    /**
     * Get whether to send location data
     */
    fun getSendLocation(context: Context): Boolean =
        settingsPreferences(context).getBoolean(KEY_SEND_LOCATION, DEFAULT_SEND_LOCATION)

    /**
     * Set whether to send location data
     */
    fun setSendLocation(context: Context, enabled: Boolean) {
        settingsPreferences(context).edit { putBoolean(KEY_SEND_LOCATION, enabled) }
    }

    /**
     * Get whether to send heading data
     */
    fun getSendHeading(context: Context): Boolean =
        settingsPreferences(context).getBoolean(KEY_SEND_HEADING, DEFAULT_SEND_HEADING)

    /**
     * Set whether to send heading data
     */
    fun setSendHeading(context: Context, enabled: Boolean) {
        settingsPreferences(context).edit { putBoolean(KEY_SEND_HEADING, enabled) }
    }

    /**
     * Get whether to send pressure data
     */
    fun getSendPressure(context: Context): Boolean =
        settingsPreferences(context).getBoolean(KEY_SEND_PRESSURE, DEFAULT_SEND_PRESSURE)

    /**
     * Set whether to send pressure data
     */
    fun setSendPressure(context: Context, enabled: Boolean) {
        settingsPreferences(context).edit { putBoolean(KEY_SEND_PRESSURE, enabled) }
    }
}
