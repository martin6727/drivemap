package com.drivemap.app.service

import com.drivemap.app.i18n.t
import com.drivemap.app.i18n.tn
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.drivemap.app.MainActivity
import com.drivemap.app.R
import com.drivemap.app.core.Core
import com.drivemap.app.geo.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps GPS, navigation, radar alerts and convoy sharing alive while the screen is off.
 */
class DriveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var wake: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL = "drive"
        const val ID = 42
        @Volatile var running = false
            private set
        private var instance: DriveService? = null
        fun refresh() { instance?.update() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Driving"), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            // e.g. location permission revoked: run without background mode instead of crashing
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        instance = this
        Core.location.start()
        Core.reports.setActive(true)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriveMap:drive")
                .also { it.acquire(6 * 60 * 60 * 1000L) }
        }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                update()
                delay(5000)
            }
        }
        return START_STICKY
    }

    fun update() {
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private fun build(): Notification {
        val nav = Core.navigator.state.value
        val convoy = Core.convoy.state.value
        val title: String
        val text: String
        if (nav != null) {
            val dest = nav.request.stops.lastOrNull()?.name ?: t("destination")
            title = t("Navigating to {0}", dest)
            val eta = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nav.etaMs))
            text = "${Geo.formatDuration(nav.remainingS)} · ${Geo.formatDistance(nav.remainingM)} · " + t("arrive {0}", eta)
        } else if (convoy != null) {
            title = t("Convoy active")
            text = tn(convoy.members.count { !it.isMe }, "Sharing your location with {0} other driver", "Sharing your location with {0} other drivers")
        } else {
            title = t("Drive mode")
            text = t("Radar alerts and the fine meter are on")

        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    override fun onDestroy() {
        running = false
        instance = null
        ticker?.cancel()
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }
}
