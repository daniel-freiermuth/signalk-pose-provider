package com.signalk.companion

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.signalk.companion.ui.theme.SignalKCompanionTheme
import com.signalk.companion.ui.main.MainScreen
import com.signalk.companion.util.BatteryOptimizationHelper
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check battery optimization status after a short delay to let the UI load
        window.decorView.post {
            checkBatteryOptimization()
        }
        
        setContent {
            SignalKCompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
    
    private fun checkBatteryOptimization() {
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            showBatteryOptimizationDialog()
        }
    }
    
    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Background Operation Required")
            .setMessage("For reliable marine navigation, this app needs to run continuously in the background. Please disable battery optimization to ensure uninterrupted GPS tracking and data transmission.")
            .setPositiveButton("Disable Optimization") { _, _ ->
                requestBatteryOptimizationExemption()
            }
            .setNegativeButton("Manual Settings") { _, _ ->
                openBatterySettings()
            }
            .setNeutralButton("Skip") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }
    
    private fun requestBatteryOptimizationExemption() {
        val intent = BatteryOptimizationHelper.createBatteryOptimizationIntent(this)
        if (intent != null) {
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback to manual settings if direct request fails
                openBatterySettings()
            }
        } else {
            openBatterySettings()
        }
    }
    
    private fun openBatterySettings() {
        try {
            val intent = BatteryOptimizationHelper.createAppBatterySettingsIntent(this)
            startActivity(intent)
        } catch (e: Exception) {
            // If even this fails, show instructions
            showBatteryInstructions()
        }
    }
    
    private fun showBatteryInstructions() {
        val instructions = BatteryOptimizationHelper.getBackgroundOptimizationInstructions()
        val message = instructions.joinToString("\n\n")
        
        AlertDialog.Builder(this)
            .setTitle("Manual Battery Optimization Setup")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
