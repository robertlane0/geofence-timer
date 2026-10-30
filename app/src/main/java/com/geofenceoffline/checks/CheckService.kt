package com.geofenceoffline.checks

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.geofenceoffline.data.CheckReason
import com.geofenceoffline.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Short-lived location foreground service. It runs for one fix at a time, serialises overlapping
 * alarms, holds a partial wake lock, and always releases providers, wake lock, and the foreground
 * notification. All scheduling survives independently in AlarmManager.
 */
class CheckService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeChecks = AtomicInteger(0)
    private val checkMutex = Mutex()
    private val wakeLocks = Collections.synchronizedSet(HashSet<PowerManager.WakeLock>())

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scheduleId = intent?.getStringExtra(EXTRA_SCHEDULE_ID)
        val scheduledAt = intent?.getLongExtra(EXTRA_SCHEDULED_AT, 0L) ?: 0L
        if (scheduleId.isNullOrBlank() || scheduledAt <= 0L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeChecks.incrementAndGet()
        val wakeLock = acquireWakeLock()
        val foregroundStarted = try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NotificationHelper.ONGOING_ID,
                    NotificationHelper.ongoingNotification(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NotificationHelper.ONGOING_ID, NotificationHelper.ongoingNotification(this))
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not enter foreground (${e.javaClass.simpleName})")
            false
        }

        if (!foregroundStarted) {
            scope.launch {
                runCatching {
                    LocationCheckRunner(applicationContext)
                        .recordFailure(scheduleId, scheduledAt, CheckReason.PERMISSION_DENIED)
                }
                finishOne(startId, wakeLock)
            }
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                checkMutex.withLock {
                    val outcome = LocationCheckRunner(applicationContext).run(scheduleId, scheduledAt)
                    if (outcome.insertedCount > 0 && SettingsRepository(applicationContext).load().notifyResults) {
                        NotificationHelper.notifyResults(applicationContext, outcome.results)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Check failed (${e.javaClass.simpleName})")
                runCatching {
                    LocationCheckRunner(applicationContext)
                        .recordFailure(scheduleId, scheduledAt, CheckReason.INTERNAL_ERROR)
                }
            } finally {
                finishOne(startId, wakeLock)
            }
        }
        return START_NOT_STICKY
    }

    private fun finishOne(startId: Int, wakeLock: PowerManager.WakeLock?) {
        releaseWakeLock(wakeLock)
        val remaining = activeChecks.decrementAndGet()
        if (remaining <= 0) {
            activeChecks.set(0)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeoFenceOffline:check").apply {
            setReferenceCounted(false)
            acquire(5 * 60 * 1000L)
        }.also { wakeLocks.add(it) }
    } catch (_: Exception) {
        null
    }

    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        if (lock == null) return
        runCatching { if (lock.isHeld) lock.release() }
        wakeLocks.remove(lock)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLocks.toList().forEach { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLocks.clear()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CheckService"
        private const val EXTRA_SCHEDULE_ID = "schedule_id"
        private const val EXTRA_SCHEDULED_AT = "scheduled_at"

        fun createIntent(context: Context, scheduleId: String, scheduledAt: Long): Intent =
            Intent(context, CheckService::class.java).apply {
                putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
            }

        fun startNow(context: Context, scheduleId: String, scheduledAt: Long = System.currentTimeMillis()) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, createIntent(context, scheduleId, scheduledAt)
            )
        }
    }
}
