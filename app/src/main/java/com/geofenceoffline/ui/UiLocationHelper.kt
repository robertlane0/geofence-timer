package com.geofenceoffline.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * One-shot location lookup used only to center the map or pick a starting point. This is UI-only:
 * the returned fix is never stored. Scheduled checks use [com.geofenceoffline.checks.LocationFixSource]
 * instead, which enforces the accuracy threshold and a strict timeout.
 */
object UiLocationHelper {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Requests a current location, falling back to a recent last-known fix. Returns a cancellation
     * function which the caller must invoke when the view is destroyed.
     */
    fun requestCurrentLocation(
        context: Context,
        onResult: (Location?) -> Unit
    ): () -> Unit {
        if (!hasPermission(context)) {
            onResult(null)
            return {}
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: run {
                onResult(null)
                return {}
            }
        val providers = buildList {
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null
            ).filterNotNull().forEach { provider ->
                if (runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) {
                    add(provider)
                }
            }
        }
        if (providers.isEmpty()) {
            onResult(recentLastKnown(manager))
            return {}
        }
        val fallback = recentLastKnown(manager)
        if (fallback != null && System.currentTimeMillis() - fallback.time < 60_000L) {
            onResult(fallback)
            return {}
        }

        val handler = Handler(Looper.getMainLooper())
        var delivered = false
        fun deliver(location: Location?) {
            if (delivered) return
            delivered = true
            handler.removeCallbacksAndMessages(null)
            onResult(location ?: fallback)
        }

        if (Build.VERSION.SDK_INT >= 30) {
            val signal = CancellationSignal()
            val provider = providers.first()
            try {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    deliver(location)
                }
            } catch (_: SecurityException) {
                deliver(null)
                return {}
            } catch (_: Exception) {
                deliver(fallback)
                return {}
            }
            val timeout = Runnable {
                runCatching { signal.cancel() }
                deliver(null)
            }
            handler.postDelayed(timeout, 12_000L)
            return {
                handler.removeCallbacksAndMessages(null)
                runCatching { signal.cancel() }
            }
        }

        @Suppress("DEPRECATION")
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = deliver(location)

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(providers.first(), listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            deliver(fallback)
            return {}
        } catch (_: Exception) {
            deliver(fallback)
            return {}
        }
        val timeout = Runnable {
            runCatching { manager.removeUpdates(listener) }
            deliver(null)
        }
        handler.postDelayed(timeout, 12_000L)
        return {
            handler.removeCallbacksAndMessages(null)
            runCatching { manager.removeUpdates(listener) }
        }
    }

    private fun recentLastKnown(manager: LocationManager): Location? {
        val now = System.currentTimeMillis()
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null
        ).filterNotNull().mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { now - it.time < 30 * 60_000L }
            .maxByOrNull { it.time }
    }
}
