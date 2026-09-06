package com.signalk.companion.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {
    
    /**
     * Check if the app is whitelisted from battery optimization
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    
    /**
     * Create an intent to request battery optimization whitelist
     */
    fun createBatteryOptimizationIntent(context: Context): Intent {
        return Intent().apply {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = Uri.parse("package:${context.packageName}")
        }
    }
    
    /**
     * Create an intent to open app-specific battery optimization settings
     */
    fun createAppBatterySettingsIntent(context: Context): Intent {
        return Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            data = Uri.parse("package:${context.packageName}")
        }
    }
    
    /**
     * Get user-friendly instructions for ensuring background operation
     */
    fun getBackgroundOptimizationInstructions(): List<String> {
        return listOf(
            "1. Disable battery optimization for this app",
            "2. Turn off 'Adaptive Battery' or 'Battery Optimization' in device settings",
            "3. Add this app to 'Never Sleeping Apps' or similar whitelist",
            "4. Disable 'Put unused apps to sleep' for this app",
            "5. Enable 'Allow background activity' for this app",
            "6. For some manufacturers (Samsung, Huawei, Xiaomi): Check brand-specific power management settings"
        )
    }
}
