package io.github.randomtools3377.keeppageloading

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app, and so every tab's page, running while the screen is off or the app is closed.
 *
 * Android lets an app run in the background like this only while it shows a notification, the
 * way a music player does. It also keeps the phone's processor awake, and on older phones its
 * Wi-Fi, so the pages can carry on working and loading.
 *
 * The notification says how each page is doing. When a page seems to have stopped, a second
 * notification buzzes and sounds once.
 */
class KeepRunningService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Tabs.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification(this))
            }
        } catch (e: RuntimeException) {
            // Android didn't allow it, which can happen when it restarts the app in the
            // background. The pages still run whenever the app is open.
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        holdLocks()
        // No intent means Android closed the app and has now started it again: open the tabs.
        if (intent == null) Tabs.restore()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        releaseLocks()
        super.onDestroy()
    }

    // No time limit: the lock is held exactly as long as the user wants the pages kept running,
    // and let go when they tap Stop.
    @SuppressLint("WakelockTimeout")
    private fun holdLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KeepPageLoading:page")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        // Newer phones keep Wi-Fi working while asleep anyway, and ignore this lock.
        if (wifiLock == null && Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KeepPageLoading:page")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val STOPPED_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "keeping"
        private const val STOPPED_CHANNEL_ID = "stopped"
        private const val ACTION_STOP = "io.github.randomtools3377.keeppageloading.STOP"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, KeepRunningService::class.java))
        }

        private fun mayNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** Shows the pages' latest news in the notification. */
        @SuppressLint("MissingPermission") // Checked in mayNotify.
        fun refresh(context: Context) {
            if (!running || !mayNotify(context)) return
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification(context))
        }

        /** These pages seem to have stopped: buzz and sound once, and say which. */
        @SuppressLint("MissingPermission") // Checked in mayNotify.
        fun alertStopped(context: Context, stopped: List<Tab>) {
            if (!mayNotify(context)) return
            makeChannels(context)
            val first = stopped.first()
            val minutes = Tabs.quietMinutes(first)
            val sites = stopped.joinToString(", ") { it.host ?: context.getString(R.string.new_tab_page) }
            val text = context.resources.getQuantityString(R.plurals.alert_text, minutes, sites, minutes)
            val alert = NotificationCompat.Builder(context, STOPPED_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.resources.getQuantityString(R.plurals.alert_title, stopped.size, stopped.size))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openTab(context, first))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
            NotificationManagerCompat.from(context).notify(STOPPED_NOTIFICATION_ID, alert)
        }

        /** No page seems stopped any more. */
        fun clearStoppedAlert(context: Context) {
            NotificationManagerCompat.from(context).cancel(STOPPED_NOTIFICATION_ID)
        }

        private fun makeChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_description)
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(STOPPED_CHANNEL_ID, context.getString(R.string.stopped_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.stopped_channel_description)
                    enableVibration(true)
                },
            )
        }

        private fun openTab(context: Context, tab: Tab?): PendingIntent = PendingIntent.getActivity(
            context,
            (tab?.id ?: 0L).toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (tab != null) putExtra(MainActivity.EXTRA_TAB_ID, tab.id) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun notification(context: Context): Notification {
            makeChannels(context)
            val stop = PendingIntent.getService(
                context, 1,
                Intent(context, KeepRunningService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val pages = Tabs.all.filter { it.address != null }
            val stopped = pages.filter { it.stopped }
            val res = context.resources
            val title = when {
                stopped.isNotEmpty() -> res.getQuantityString(R.plurals.notification_title_stopped, stopped.size, stopped.size)
                pages.any { it.connectionFailing } -> context.getString(R.string.notification_title_waiting)
                pages.isEmpty() -> context.getString(R.string.notification_title_none)
                else -> res.getQuantityString(R.plurals.notification_title_running, pages.size, pages.size)
            }
            val text = pages.joinToString("\n") { "${it.host ?: it.address}: ${Tabs.statusOf(context, it)}" }
                .ifEmpty { context.getString(R.string.notification_no_page) }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openTab(context, stopped.firstOrNull()))
                .addAction(0, context.getString(R.string.notification_stop), stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
