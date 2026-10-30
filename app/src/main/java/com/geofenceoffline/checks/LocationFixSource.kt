package com.geofenceoffline.checks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed class FixOutcome {
    /** The location exists only in this object and is never persisted or logged. */
    data class Success(val location: Location) : FixOutcome()
    data class Inaccurate(val bestAccuracyMeters: Float?) : FixOutcome()
    data object NoFix : FixOutcome()
    data object PermissionDenied : FixOutcome()
    data object Disabled : FixOutcome()
}

interface FixSource {
    suspend fun acquire(accuracyThresholdMeters: Int, timeoutSeconds: Int): FixOutcome
}

/**
 * Requests a fresh fix from platform providers. No continuous tracking: providers are registered
 * only for the duration of one scheduled check and are always removed afterwards.
 */
class LocationFixSource(private val context: Context) : FixSource {
    override suspend fun acquire(
        accuracyThresholdMeters: Int,
        timeoutSeconds: Int
    ): FixOutcome = withContext(Dispatchers.Main) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext FixOutcome.PermissionDenied
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext FixOutcome.NoFix
        val enabled = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
        if (!enabled) return@withContext FixOutcome.Disabled

        var bestAccuracy: Float? = null
        val providers = buildList {
            if (manager.allProviders.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31 && manager.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }
        if (providers.isEmpty()) return@withContext FixOutcome.NoFix

        val result = withTimeoutOrNull(timeoutSeconds.coerceIn(5, 300) * 1000L) {
            suspendCancellableCoroutine { continuation ->
                val listeners = ArrayList<Pair<String, LocationListener>>()
                var finished = false

                fun finish(outcome: FixOutcome) {
                    if (finished) return
                    finished = true
                    listeners.forEach { (provider, listener) ->
                        runCatching { manager.removeUpdates(listener) }
                    }
                    if (continuation.isActive) continuation.resume(outcome)
                }

                for (provider in providers) {
                    if (finished) break
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (location.hasAccuracy()) {
                                val accuracy = location.accuracy
                                if (bestAccuracy == null || accuracy < bestAccuracy!!) bestAccuracy = accuracy
                                if (accuracy <= accuracyThresholdMeters) {
                                    finish(FixOutcome.Success(location))
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    try {
                        manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                        listeners.add(provider to listener)
                    } catch (e: SecurityException) {
                        finish(FixOutcome.PermissionDenied)
                        break
                    } catch (_: Exception) {
                        // Provider can be unavailable despite being listed.
                    }
                }

                if (listeners.isEmpty()) {
                    finish(FixOutcome.NoFix)
                } else {
                    continuation.invokeOnCancellation {
                        listeners.forEach { (_, listener) ->
                            runCatching { manager.removeUpdates(listener) }
                        }
                    }
                }
            }
        }
        if (result == null) {
            if (bestAccuracy != null) FixOutcome.Inaccurate(bestAccuracy)
            else FixOutcome.NoFix
        } else {
            result
        }
    }
}
