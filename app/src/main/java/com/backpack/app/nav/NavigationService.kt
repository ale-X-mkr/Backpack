package com.backpack.app.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.backpack.app.MainActivity
import com.backpack.app.Prefs
import com.backpack.app.R
import com.backpack.app.core.Progress
import com.backpack.app.core.RouteResult
import com.backpack.app.core.Speech
import java.util.Calendar

/**
 * Keeps navigation alive with the screen off or the app in the
 * background, with a visible notification (next instruction, distance,
 * arrival time, Stop). Nothing is hidden: no notification, no service.
 */
class NavigationService : Service() {

    private var lastText = ""

    private val navListener = object : NavigationEngine.Listener {
        override fun onProgress(progress: Progress, route: RouteResult) = update(progress, route)
        override fun onRerouting() = post("Recalculating route…", "")
        override fun onStopped() = stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val n = build("Navigation", "Starting…")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        NavigationEngine.addListener(navListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NavigationEngine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!NavigationEngine.isActive) stopSelf()
        return START_NOT_STICKY
    }

    /** Swiping the app away from recent apps ends navigation too. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        NavigationEngine.stop()
        stopSelf()
    }

    override fun onDestroy() {
        NavigationEngine.removeListener(navListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun update(p: Progress, route: RouteResult) {
        val metric = Prefs.useMetric(this)
        val step = if (p.nextStepIndex >= 0) route.steps.getOrNull(p.nextStepIndex) else null
        val title = when {
            p.arrived -> "Arrived"
            p.offRoute -> "Off route"
            step != null -> step.instruction
            else -> "Head to your destination"
        }
        val arrival = Calendar.getInstance().apply { add(Calendar.SECOND, p.remainingS.toInt()) }
        val text = "${Speech.shortDistance(p.distanceToNextM, metric)} · " +
            "${Speech.shortDistance(p.remainingM, metric)} left · arrive %02d:%02d".format(
                arrival.get(Calendar.HOUR_OF_DAY), arrival.get(Calendar.MINUTE)
            )
        post(title, text)
    }

    private fun post(title: String, text: String) {
        if ("$title|$text" == lastText) return
        lastText = "$title|$text"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIFICATION_ID, build(title, text)) } catch (e: SecurityException) { }
    }

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation_go)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Turn-by-turn guidance while navigating"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.backpack.app.STOP_NAVIGATION"
    }
}
