package com.backpack.app.transit

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.backpack.app.MainActivity
import com.backpack.app.R
import com.backpack.app.core.Geo
import com.backpack.app.core.LatLon
import com.backpack.app.core.TransitAlarm
import com.backpack.app.location.LocationHub

/** 1.1.0: what the screen shows about the transit alarm (main thread). */
object TransitSession {
    var active = false
    var ringing = false
    var destination: LatLon? = null
    var lead: TransitAlarm.Lead = TransitAlarm.DEFAULT_LEAD
    /** Backup "wake at" time (epoch ms), 0 = none. */
    var backupAtMs = 0L
    var trackM: Double? = null
    var etaS: Double? = null
    /** No GPS for a while: distance estimated from the last speed. */
    var estimated = false

    /** Kept on the phone while the alarm is on: Android may close the app,
     *  and the backup time must still ring. */
    fun save(c: Context) {
        val d = destination
        c.getSharedPreferences("transit", Context.MODE_PRIVATE).edit().apply {
            if (!active || d == null) clear() else {
                putString("dest", "${d.lat},${d.lon}")
                putInt("leadMin", lead.minutes)
                putInt("leadKm", lead.km)
                putLong("backup", backupAtMs)
            }
        }.apply()
    }

    /** After Android closed the app: the alarm that was on, if any. */
    fun restore(c: Context): Boolean {
        if (active) return true
        val p = c.getSharedPreferences("transit", Context.MODE_PRIVATE)
        val d = p.getString("dest", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() } ?: return false
        if (d.size != 2) return false
        destination = LatLon(d[0], d[1])
        lead = TransitAlarm.Lead(p.getInt("leadMin", 5), p.getInt("leadKm", 0))
        backupAtMs = p.getLong("backup", 0L)
        active = true
        return true
    }

    fun interface Listener { fun onTransitUpdate() }
    private val listeners = mutableListOf<Listener>()
    fun addListener(l: Listener) { if (l !in listeners) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    fun changed() { for (l in listeners.toList()) l.onTransitUpdate() }
}

/**
 * 1.1.0: wake-up alarm on a bus or train. Runs with the screen off (visible
 * notification), asks the GPS rarely while far from the stop (up to every
 * 5 min) and every 10 s when close, rings at alarm volume with vibration,
 * also in silent mode. In a tunnel it goes on with the last speed. An
 * optional "wake at" time rings anyway (Android's alarm clock).
 */
class TransitAlarmService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val speed = TransitAlarm.Speed()
    private var lastFixAtMs = 0L
    private var lastTrackM: Double? = null
    private var pace = 30_000L
    private var player: MediaPlayer? = null
    private var tracking = false

    private val fixListener = LocationHub.Listener { location, plausible -> if (plausible) onFix(location) }

    /** Every 30 s: without GPS for a while, the distance from the last speed. */
    private val tick = object : Runnable {
        override fun run() {
            if (!TransitSession.active || TransitSession.ringing) return
            val last = lastTrackM
            val v = speed.value
            val gap = System.currentTimeMillis() - lastFixAtMs
            if (last != null && v != null && v >= 2.0 && gap > maxOf(2 * pace, 60_000L)) {
                val est = (last - v * gap / 1000.0).coerceAtLeast(0.0)
                TransitSession.trackM = est
                TransitSession.etaS = TransitAlarm.etaSeconds(est, v)
                TransitSession.estimated = true
                TransitSession.changed()
                post()
                if (TransitAlarm.shouldRing(est, v, TransitSession.lead)) ring("Your stop is near (estimated, no GPS)")
            }
            val b = TransitSession.backupAtMs
            if (b > 0 && System.currentTimeMillis() >= b) ring("Wake-up time")
            main.postDelayed(this, 30_000L)
        }
    }

    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    /** Foreground at once (Android requires it): location while tracking;
     *  for the backup ring (maybe with the app closed, where location is
     *  not allowed) as media playback -- it only plays the alarm. */
    private fun goForeground(ringOnly: Boolean) {
        if (foreground) return
        foreground = true
        val type = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
            ringOnly -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build("Wake-up alarm", "Starting…", false), type)
        } catch (e: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { goForeground(true); stopAll(); return START_NOT_STICKY }
            ACTION_RING -> {
                goForeground(!tracking)
                if (!TransitSession.restore(this)) { stopAll(); return START_NOT_STICKY }
                ring("Wake-up time")
                return START_NOT_STICKY
            }
        }
        goForeground(false)
        if (!TransitSession.active || TransitSession.destination == null) { stopAll(); return START_NOT_STICKY }
        if (!tracking) {
            tracking = true
            LocationHub.addListener(fixListener)
            LocationHub.setTransitPace(this, pace)
            LocationHub.acquire(this, LocationHub.LEASE_TRANSIT)
            LocationHub.lastGoodFix?.let { onFix(it) }
            main.postDelayed(tick, 30_000L)
        }
        scheduleBackup()
        post()
        return START_NOT_STICKY
    }

    /** Swiping the app away keeps the alarm (that is its job). */
    override fun onTaskRemoved(rootIntent: Intent?) {}

    override fun onDestroy() {
        stopSound()
        if (tracking) {
            LocationHub.removeListener(fixListener)
            LocationHub.release(this, LocationHub.LEASE_TRANSIT)
        }
        main.removeCallbacks(tick)
        main.removeCallbacks(silence)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onFix(location: Location) {
        if (!TransitSession.active || TransitSession.ringing) return
        val dest = TransitSession.destination ?: return
        if (location.hasSpeed()) speed.add(location.speed.toDouble())
        lastFixAtMs = System.currentTimeMillis()
        val track = TransitAlarm.trackDistance(Geo.distance(LatLon(location.latitude, location.longitude), dest))
        lastTrackM = track
        val v = speed.value
        TransitSession.trackM = track
        TransitSession.etaS = TransitAlarm.etaSeconds(track, v)
        TransitSession.estimated = false
        TransitSession.changed()
        if (TransitAlarm.shouldRing(track, v, TransitSession.lead)) { ring("Your stop is near"); return }
        pace = TransitAlarm.nextPaceMs(track, v, TransitSession.lead)
        LocationHub.setTransitPace(this, pace)
        post()
    }

    /** Never ring forever: silence after 5 min (the notification stays). */
    private val silence = Runnable { stopSound() }

    private fun ring(why: String) {
        if (TransitSession.ringing) return
        TransitSession.active = true
        TransitSession.ringing = true
        TransitSession.changed()
        // GPS no longer needed.
        if (tracking) {
            tracking = false
            LocationHub.removeListener(fixListener)
            LocationHub.release(this, LocationHub.LEASE_TRANSIT)
        }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@TransitAlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) { }
        try {
            @Suppress("DEPRECATION")
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800, 400), 0))
        } catch (e: Exception) { }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build("Wake up! $why", "Tap Stop to silence", true))
        main.removeCallbacks(silence)
        main.postDelayed(silence, 5 * 60_000L)
    }

    private fun stopSound() {
        main.removeCallbacks(silence)
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        try {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        } catch (e: Exception) { }
    }

    private fun stopAll() {
        stopSound()
        cancelBackup()
        TransitSession.active = false
        TransitSession.ringing = false
        TransitSession.trackM = null
        TransitSession.etaS = null
        TransitSession.save(this)
        TransitSession.changed()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- Backup "wake at" ----------

    private fun backupIntent(): PendingIntent = PendingIntent.getForegroundService(
        this, 2, Intent(this, TransitAlarmService::class.java).setAction(ACTION_RING),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleBackup() {
        val at = TransitSession.backupAtMs
        cancelBackup()
        if (at <= System.currentTimeMillis()) return
        // Android's alarm clock (exact, even in deep sleep); if the phone does
        // not allow it, the 30 s check of this service rings instead.
        try {
            val am = getSystemService(AlarmManager::class.java) ?: return
            val show = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), backupIntent())
        } catch (e: Exception) { }
    }

    private fun cancelBackup() {
        try { getSystemService(AlarmManager::class.java)?.cancel(backupIntent()) } catch (e: Exception) { }
    }

    // ---------- Notification ----------

    private fun post() {
        if (TransitSession.ringing) return
        val t = TransitSession.trackM
        val text = if (t == null) "Waiting for GPS…" else {
            val km = "%.1f km".format(t / 1000.0)
            val eta = TransitSession.etaS?.let { " · ~${(it / 60).toInt()} min" } ?: ""
            "$km$eta${if (TransitSession.estimated) " (estimated)" else ""} · alarm ${TransitSession.lead.label}"
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build("Wake-up alarm on", text, false))
    }

    private fun build(title: String, text: String, alarm: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TransitAlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, if (alarm) CHANNEL_ALARM else CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_mode_transit)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(!alarm)
            .setContentIntent(open)
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (alarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Wake-up alarm status", NotificationManager.IMPORTANCE_LOW))
        // The sound comes from the alarm player; the channel only pops up.
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALARM, "Wake-up alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
        })
    }

    companion object {
        const val ACTION_STOP = "com.backpack.app.transit.STOP"
        const val ACTION_RING = "com.backpack.app.transit.RING"
        private const val CHANNEL_STATUS = "transit_status"
        private const val CHANNEL_ALARM = "transit_alarm"
        private const val NOTIFICATION_ID = 43
    }
}
