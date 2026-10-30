package com.geofenceoffline.checks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

/**
 * Restores alarms after reboot, package update, manual clock changes, or time-zone changes.
 * Missed checks while the device was off are not back-filled: the next future occurrence is used,
 * which prevents a burst of stale location checks after power-on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pendingResult = goAsync()
                EXECUTOR.execute {
                    try {
                        AlarmScheduler(context).rescheduleAll()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not restore alarms (${e.javaClass.simpleName})")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
