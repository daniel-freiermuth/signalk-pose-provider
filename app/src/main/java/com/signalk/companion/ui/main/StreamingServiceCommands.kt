package com.signalk.companion.ui.main

import android.content.Context
import android.content.Intent

/**
 * Sends [intent] to SignalKStreamingService. The framework answers `null` only when the
 * service is not declared in the manifest — a build defect, so it fails loudly instead of
 * the command vanishing.
 */
internal fun Context.sendToStreamingService(intent: Intent, foreground: Boolean = false) {
    val component = if (foreground) startForegroundService(intent) else startService(intent)
    checkNotNull(component) { "SignalKStreamingService is not declared in the manifest" }
}
