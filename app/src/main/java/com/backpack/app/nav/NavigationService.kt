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
 *
 * 1.3.0: also while Backpack is open without navigation ("Backpack is
 * running"): the speed alarm keeps watching by car with the screen off
 * (GPS 1 s while moving, every 30 s after 3 min standing still), until
 * Exit (notification or Settings) or the app is swiped away.
 */
class NavigationService : Service() {

    private var lastText = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastMovingAtMs = 0L

    private val navListener = object : NavigationEngine.Listener {
        override fun onProgress(progress: Progress, route: RouteResult) = update(progress, route)
        override fun onRerouting() = post(t(R.string.other_notif_recalculating), "")
        override fun onStopped() {
            if (AppRunning.open) { showRunning(); updateBackground() } else stopSelf()
        }
    }

    private val moveListener = com.backpack.app.location.LocationHub.Listener { loc, ok ->
        // Slow city traffic still counts as moving (~3 km/h).
        if (ok && loc.hasSpeed() && loc.speed > 0.8f) lastMovingAtMs = System.currentTimeMillis()
        updateBackground()
    }

    private val periodic = object : Runnable {
        override fun run() { updateBackground(); handler.postDelayed(this, 30_000L) }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val n = if (NavigationEngine.isActive) build(t(R.string.other_notif_navigation), t(R.string.other_notif_starting)) else buildRunning()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        NavigationEngine.addListener(navListener)
        com.backpack.app.speed.SpeedGuard.start(this)
        com.backpack.app.location.LocationHub.addListener(moveListener)
        AppRunning.onChange = { updateBackground() }
        lastMovingAtMs = System.currentTimeMillis()
        handler.post(periodic)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> { exitApp(this); return START_NOT_STICKY }
            ACTION_STOP -> {
                NavigationEngine.stop()
                if (!AppRunning.open) { stopSelf(); return START_NOT_STICKY }
            }
        }
        if (!NavigationEngine.isActive && !AppRunning.open) { stopSelf(); return START_NOT_STICKY }
        if (!NavigationEngine.isActive) showRunning()
        updateBackground()
        return START_NOT_STICKY
    }

    /** Swiping the app away from recent apps = closing it: everything stops. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        exitApp(this)
    }

    override fun onDestroy() {
        NavigationEngine.removeListener(navListener)
        com.backpack.app.location.LocationHub.removeListener(moveListener)
        com.backpack.app.location.LocationHub.release(this, com.backpack.app.location.LocationHub.LEASE_BG)
        handler.removeCallbacksAndMessages(null)
        if (AppRunning.onChange != null) AppRunning.onChange = null
        super.onDestroy()
    }

    /**
     * Screen off / app behind, no navigation, by car: GPS for the speed alarm
     * (1 s while moving, 30 s after 3 min standing still). Otherwise the
     * screen or the navigation hold the GPS, or nothing does (walking or
     * cycling without a route: no GPS in the background).
     */
    private fun updateBackground() {
        val hub = com.backpack.app.location.LocationHub
        val need = AppRunning.open && !AppRunning.uiVisible && !NavigationEngine.isActive &&
            com.backpack.app.speed.SpeedGuard.driving(this) && hub.hasPermission(this)
        if (!need) {
            if (hub.holds(hub.LEASE_BG)) hub.release(this, hub.LEASE_BG)
            return
        }
        val moving = System.currentTimeMillis() - lastMovingAtMs < 180_000L
        hub.setBgPace(this, if (moving) 1000L else 30_000L)
        if (!hub.holds(hub.LEASE_BG)) hub.acquire(this, hub.LEASE_BG)
    }

    private fun showRunning() {
        lastText = ""
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIFICATION_ID, buildRunning()) } catch (e: SecurityException) { }
    }

    private fun buildRunning(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exit = PendingIntent.getService(
            this, 2,
            Intent(this, NavigationService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation_go)
            .setContentTitle(t(R.string.other_notif_running))
            .setContentText(t(R.string.other_notif_running_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, t(R.string.other_notif_exit), exit)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun update(p: Progress, route: RouteResult) {
        val metric = Prefs.useMetric(this)
        val step = if (p.nextStepIndex >= 0) route.steps.getOrNull(p.nextStepIndex) else null
        val title = when {
            p.arrived -> t(R.string.other_notif_arrived)
            p.offRoute -> t(R.string.other_notif_off_route)
            step != null -> step.instruction
            else -> t(R.string.other_notif_head_to_destination)
        }
        val arrival = Calendar.getInstance().apply { add(Calendar.SECOND, p.remainingS.toInt()) }
        val text = "${Speech.shortDistance(p.distanceToNextM, metric)} · " +
            t(R.string.other_notif_left_arrive, Speech.shortDistance(p.remainingM, metric),
                arrival.get(Calendar.HOUR_OF_DAY), arrival.get(Calendar.MINUTE))
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
            .addAction(0, t(R.string.other_notif_stop), stop)
            .addAction(0, t(R.string.other_notif_exit), PendingIntent.getService(
                this, 2, Intent(this, NavigationService::class.java).setAction(ACTION_EXIT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    /** 1.6.0: texts in the screen language (the service context keeps the phone's). */
    private fun t(id: Int, vararg args: Any?): String = com.backpack.app.L10n.str(this, id, *args)

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Created every time: Android keeps the user's channel settings and only
        // updates its name / description (they follow the screen language).
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, t(R.string.other_notif_navigation), NotificationManager.IMPORTANCE_LOW).apply {
                description = t(R.string.other_notif_channel_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.backpack.app.STOP_NAVIGATION"
        const val ACTION_EXIT = "com.backpack.app.EXIT"
        const val ACTION_RUN = "com.backpack.app.RUN"

        /** The map is shown: Backpack is open from now on (until Exit). */
        fun keepRunning(c: Context) {
            AppRunning.open = true
            if (!com.backpack.app.location.LocationHub.hasPermission(c)) return
            try {
                androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, NavigationService::class.java).setAction(ACTION_RUN))
            } catch (e: Exception) {
                // Android refused (rare): everything still works while the map is shown.
            }
        }

        /** Exit: navigation, speed alarm, GPS and the service stop; the map closes. */
        fun exitApp(c: Context) {
            AppRunning.open = false
            NavigationEngine.stop()
            com.backpack.app.location.LocationHub.release(c, com.backpack.app.location.LocationHub.LEASE_BG)
            com.backpack.app.speed.SpeedGuard.stop()
            c.stopService(Intent(c, NavigationService::class.java))
            AppRunning.onExit?.invoke()
        }
    }
}
