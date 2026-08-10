package com.signalk.companion.service

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.signalk.companion.data.model.LocationData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GNSS position source.
 *
 * Uses [LocationManager] with [LocationManager.GPS_PROVIDER] directly, **not** the Play
 * Services Fused Location Provider (architectural-plan.md P8). FLP blends GNSS with
 * WiFi/cell database positions using opaque, pedestrian/car-tuned logic: it can snap to a
 * marina access point's database location or switch providers mid-track, injecting exactly
 * the discontinuities this project exists to remove. Its error mode — database teleports —
 * also violates the assumptions of the M4 filter, which expects multipath-like, gateable,
 * roughly zero-mean error.
 *
 * `GPS_PROVIDER` gives unadulterated chip output: per-fix accuracy, Doppler speed and
 * bearing with their accuracies, and no Play Services dependency (which unblocks an
 * F-Droid-clean build).
 *
 * Expect fixes at the chip's native rate, typically 1 Hz. FLP's sub-second callbacks were
 * largely interpolation and repeats; this reports only what was actually measured. Higher
 * output rates are M4's job, from the filter, not from the provider.
 */
@Singleton
class LocationService @Inject constructor() {

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    /** Application context, retained so a rate change can re-register without a caller-supplied context. */
    private var appContext: Context? = null
    private var currentIntervalMs: Long = DEFAULT_INTERVAL_MS
    private var lastLocationTime = 0L

    private val _locationUpdates = MutableStateFlow<LocationData?>(null)
    val locationUpdates: StateFlow<LocationData?> = _locationUpdates

    companion object {
        private const val TAG = "LocationService"
        private const val DEFAULT_INTERVAL_MS = 500L

        /**
         * Minimum distance between updates, metres. Zero on purpose: a distance filter
         * would suppress fixes at anchor and while drifting, which is precisely where the
         * position record matters (and where the wander *is* the signal — plan §6).
         */
        private const val MIN_DISTANCE_M = 0f
    }

    @Throws(SecurityException::class)
    // Default resolves to the last requested interval, not the compile-time default, so a
    // rate set through updateLocationRate() while inactive is honoured on the next start
    // rather than silently discarded. currentIntervalMs starts at DEFAULT_INTERVAL_MS.
    suspend fun startLocationUpdates(context: Context, updateIntervalMs: Long = currentIntervalMs) {
        Log.d(TAG, "Starting GNSS location updates with interval: ${updateIntervalMs}ms")

        // Clean up any existing registration to prevent double-delivery.
        // Don't null out _locationUpdates — preserve the last fix across reconfiguration.
        removeUpdates()

        appContext = context.applicationContext
        currentIntervalMs = updateIntervalMs
        lastLocationTime = 0L // Reset for accurate interval logging

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = manager

        // A device with no GNSS hardware has no GPS_PROVIDER at all, and both
        // isProviderEnabled() and requestLocationUpdates() reject an unknown provider.
        // Bail out cleanly rather than crashing the streaming service on a tablet.
        if (LocationManager.GPS_PROVIDER !in manager.allProviders) {
            Log.e(TAG, "Device has no GPS_PROVIDER - GNSS unavailable, no position will be published")
            locationManager = null
            appContext = null
            return
        }

        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            // Not fatal: registration stays live and fixes start flowing if the user
            // enables location later. Log it so a silent no-data state is explainable.
            Log.w(TAG, "GPS provider is disabled in system settings - no fixes until enabled")
        }

        registerListener(manager, updateIntervalMs)

        Log.d(TAG, "GNSS location updates started successfully")
    }

    fun stopLocationUpdates() {
        Log.d(TAG, "Stopping location updates")
        removeUpdates()
        locationManager = null
        appContext = null
        _locationUpdates.value = null
    }

    fun isLocationUpdatesActive(): Boolean {
        return locationManager != null && locationListener != null
    }

    @Throws(SecurityException::class)
    suspend fun updateLocationRate(context: Context, updateIntervalMs: Long) {
        Log.d(TAG, "Updating location rate to ${updateIntervalMs}ms")
        // Recorded before the active check, so a rate set while stopped survives to the
        // next start — startLocationUpdates()'s default reads this back.
        currentIntervalMs = updateIntervalMs
        if (isLocationUpdatesActive()) {
            startLocationUpdates(context, updateIntervalMs)
        }
    }

    /**
     * Change the update rate using the context captured at start.
     *
     * The previous implementation only logged, so a rate change from the running service
     * silently did nothing; retaining the application context makes it actually re-register.
     *
     * Unlike [startLocationUpdates] this does not propagate [SecurityException]: the one
     * caller is a config update inside the running foreground service, and taking the whole
     * service down mid-passage because location permission was revoked is worse than
     * logging and carrying on without fixes.
     */
    fun updateLocationRate(updateIntervalMs: Long) {
        val manager = locationManager
        if (manager == null || appContext == null) {
            Log.d(TAG, "Not active - rate change to ${updateIntervalMs}ms recorded for next start")
            currentIntervalMs = updateIntervalMs
            return
        }

        Log.d(TAG, "Updating location rate to ${updateIntervalMs}ms")
        removeUpdates()
        try {
            registerListener(manager, updateIntervalMs)
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission lost while re-registering - no further fixes", e)
        }
    }

    @Throws(SecurityException::class)
    private fun registerListener(manager: LocationManager, updateIntervalMs: Long) {
        currentIntervalMs = updateIntervalMs
        lastLocationTime = 0L

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val currentTime = System.currentTimeMillis()
                val actualInterval = if (lastLocationTime > 0) currentTime - lastLocationTime else 0
                lastLocationTime = currentTime

                Log.d(TAG, "GNSS fix received. Actual interval: ${actualInterval}ms (configured: ${currentIntervalMs}ms)")
                _locationUpdates.value = location.toLocationData()
            }

            override fun onProviderEnabled(provider: String) {
                Log.i(TAG, "Location provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.w(TAG, "Location provider disabled: $provider - fixes will stop")
            }
        }
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            updateIntervalMs,
            MIN_DISTANCE_M,
            listener,
            Looper.getMainLooper()
        )

        // Assigned only after registration succeeds. If requestLocationUpdates throws —
        // SecurityException on a revoked permission — an already-stored listener would
        // leave isLocationUpdatesActive() reporting true while no fixes flow, and the
        // service would never try to recover.
        locationListener = listener
    }

    private fun removeUpdates() {
        locationListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to remove location updates: ${e.message}")
            }
        }
        locationListener = null
    }
}

/**
 * Map an Android [Location] to our transport model.
 *
 * All fields are plain `Location` getters, not FLP features, so they survive the move off
 * Play Services unchanged. We never derive speed or bearing by differencing positions,
 * which turns multipath into fake velocity (plan P2).
 *
 * On provenance, precisely: these are whatever the GNSS engine reports. Real GNSS
 * receivers compute velocity from carrier Doppler, which is what P2 relies on, but the
 * Android API states no such guarantee — so treat "Doppler" as a well-founded expectation
 * of the hardware, not a contract. Verifying it directly needs the raw `GnssMeasurement`
 * layer, deferred under P8.
 *
 * `hasSpeedAccuracy`/`hasBearingAccuracy`/`hasVerticalAccuracy` need no SDK-version guard:
 * minSdk is 30, well past the API 26 that introduced them.
 */
private fun Location.toLocationData(): LocationData = LocationData(
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    bearing = if (hasBearing()) bearing else null,
    speed = if (hasSpeed()) speed else null,
    altitude = if (hasAltitude()) altitude else null,
    timestamp = time,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    verticalAccuracy = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    speedAccuracy = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    bearingAccuracy = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
    provider = provider
)
