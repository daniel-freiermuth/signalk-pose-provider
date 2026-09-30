package com.signalk.companion.util

import android.content.Context
import android.content.SharedPreferences

private const val PREF_NAME = "signalk_companion_settings"

/**
 * The one preferences file every `*Settings` object in this package reads and writes.
 * The settings are split by concern; the storage and its keys are shared and unchanged.
 */
internal fun settingsPreferences(context: Context): SharedPreferences =
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
