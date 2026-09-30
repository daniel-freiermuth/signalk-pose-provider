package com.signalk.companion.util

import android.content.Context
import androidx.core.content.edit

/** Update rates, and whether a stream was running when the process last died. */
object StreamingSettings {
    private const val KEY_LOCATION_INTERVAL_MS = "location_interval_ms"
    private const val KEY_SENSOR_INTERVAL_MS = "sensor_interval_ms"
    private const val KEY_WAS_STREAMING = "was_streaming"
    private const val DEFAULT_LOCATION_INTERVAL_MS = 1000L
    private const val DEFAULT_SENSOR_INTERVAL_MS = 250L

    fun getLocationIntervalMs(context: Context): Long =
        settingsPreferences(context).getLong(KEY_LOCATION_INTERVAL_MS, DEFAULT_LOCATION_INTERVAL_MS)

    fun setLocationIntervalMs(context: Context, intervalMs: Long) {
        require(intervalMs > 0) { "Location interval must be positive" }
        settingsPreferences(context).edit { putLong(KEY_LOCATION_INTERVAL_MS, intervalMs) }
    }

    fun getSensorIntervalMs(context: Context): Long =
        settingsPreferences(context).getLong(KEY_SENSOR_INTERVAL_MS, DEFAULT_SENSOR_INTERVAL_MS)

    fun setSensorIntervalMs(context: Context, intervalMs: Long) {
        require(intervalMs > 0) { "Sensor interval must be positive" }
        settingsPreferences(context).edit { putLong(KEY_SENSOR_INTERVAL_MS, intervalMs) }
    }

    /** Persists whether the service was actively streaming. Used to resume after an OS kill. */
    fun setWasStreaming(context: Context, streaming: Boolean) {
        settingsPreferences(context).edit { putBoolean(KEY_WAS_STREAMING, streaming) }
    }

    fun getWasStreaming(context: Context): Boolean =
        settingsPreferences(context).getBoolean(KEY_WAS_STREAMING, false)
}
