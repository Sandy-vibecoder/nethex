package app.phantom.spectre

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
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD

/**
 * Foreground service: keeps the local API, sensors and the presence sweep alive while the app is in the
 * background, and owns the notification channels used for fence alerts.
 */
class SpectreService : Service() {
    companion object {
        const val SERVICE_CHANNEL = "spectre_service"
        const val EVENT_CHANNEL = "spectre_events"
        const val ACTION_STOP = "app.phantom.spectre.STOP"
        @Volatile var running = false

        fun launchIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private var tel: Telemetry? = null
    private var tracker: Tracker? = null
    private var presence: Presence? = null
    private var server: SpectreServer? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, getString(R.string.svc_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(EVENT_CHANNEL, getString(R.string.ev_channel), NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!running) {
            val stop = PendingIntent.getService(this, 2, Intent(this, SpectreService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.svc_running))
                .setOngoing(true).setContentIntent(launchIntent(this))
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
            val hasLoc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            try {
                startForeground(1, n, if (hasLoc) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (_: Exception) {
                try { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) } catch (_: Exception) { startForeground(1, n) }
            }
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spectre:listener").apply { acquire() }
            } catch (_: Exception) {}
            val t = Telemetry(this); val tr = Tracker(this); val p = Presence(this, t)
            t.start(); tr.start(); p.start()
            val s = SpectreServer(this, t, tr, p, Util.now())
            try { s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) } catch (_: Exception) {}
            tel = t; tracker = tr; presence = p; server = s
            running = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { server?.stop() } catch (_: Exception) {}
        tracker?.stop(); tel?.stop(); presence?.stop()
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
