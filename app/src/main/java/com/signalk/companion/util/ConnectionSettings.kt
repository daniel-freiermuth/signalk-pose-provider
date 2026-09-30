package com.signalk.companion.util

import android.content.Context
import androidx.core.content.edit

/** The SignalK server to stream to and the credentials to log in with. */
object ConnectionSettings {
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val DEFAULT_SERVER_URL = ""

    /**
     * Get the SignalK server URL
     */
    fun getServerUrl(context: Context): String =
        settingsPreferences(context).getString(KEY_SERVER_URL, DEFAULT_SERVER_URL).orEmpty()

    /**
     * Set the SignalK server URL
     */
    fun setServerUrl(context: Context, url: String) {
        settingsPreferences(context).edit { putString(KEY_SERVER_URL, url.trim()) }
    }

    /**
     * Get the stored username
     */
    fun getUsername(context: Context): String =
        settingsPreferences(context).getString(KEY_USERNAME, "").orEmpty()

    /**
     * Set the username
     */
    fun setUsername(context: Context, username: String) {
        settingsPreferences(context).edit { putString(KEY_USERNAME, username.trim()) }
    }

    /**
     * Get the stored password (Note: stored in plain text, consider encryption for production)
     */
    fun getPassword(context: Context): String =
        settingsPreferences(context).getString(KEY_PASSWORD, "").orEmpty()

    /**
     * Set the password
     */
    fun setPassword(context: Context, password: String) {
        settingsPreferences(context).edit { putString(KEY_PASSWORD, password) }
    }

    /**
     * Check if credentials are stored
     */
    fun hasCredentials(context: Context): Boolean =
        getUsername(context).isNotBlank() && getPassword(context).isNotBlank()
}
