package com.wanderwildwood.jimeikin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Holds the app up while [DownloadQueue] has anything to do.
 *
 * A run of songs is minutes of network, and nobody sits watching Downloads for that long: they
 * put the phone down and the screen goes off, or they go back to the home screen. Either used to
 * leave the downloads to the mercy of whatever Android killed next. This says what is happening
 * in a quiet notification, holds the phone awake and the wifi up, and goes away when the queue is
 * empty. The queue does the work; this only keeps it alive and says how far along it is.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastStart = 0
    private var watching = false

    private val queue: DownloadQueue get() = (application as CalmMusic).downloadQueue

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        // Promised to the system the moment it is started, whatever it was started for: a
        // service started in the foreground that does not say so within a few seconds is killed.
        goForeground(notification(currentLine()))

        if (intent?.action == STOP) queue.cancelAll()

        holdAwake()
        if (!watching) {
            watching = true
            scope.launch {
                combine(queue.items, queue.run) { items, run -> lineOf(items, run) to items.any { it.state.isActive } }
                    .distinctUntilChanged()
                    .collect { (line, busy) ->
                        if (busy) show(notification(line)) else finish(line)
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun currentLine(): DownloadLine? = lineOf(queue.items.value, queue.run.value)

    private fun lineOf(items: List<DownloadItem>, run: List<String>): DownloadLine? =
        Downloads.line(items.filter { it.id in run })

    /**
     * Stops only if nothing has been asked since the last start: a song asked for in the moment
     * between the last download ending and the service going away would otherwise go with it.
     *
     * How the run ended goes on the line when a screen is there to show it, and into a
     * notification when not: a run that finished while the phone was in a pocket is still news
     * when it comes out.
     */
    private fun finish(line: DownloadLine?) {
        letGo()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (line is DownloadLine.Finished && !queue.isWatched) {
            getSystemService(NotificationManager::class.java).notify(
                DONE_ID,
                NotificationCompat.Builder(this, ensureChannel())
                    .setSmallIcon(R.drawable.ic_download_notification)
                    .setContentTitle(Downloads.text(line, DownloadWords(resources)))
                    .setContentIntent(openDownloads())
                    .setSilent(true)
                    .setAutoCancel(true)
                    .build(),
            )
            queue.forgetRun()
        }
        stopSelfResult(lastStart)
    }

    private fun notification(line: DownloadLine?): Notification {
        val words = DownloadWords(resources)
        val underway = line as? DownloadLine.Underway
        val title = when {
            underway == null -> getString(R.string.download_line_underway_one)
            underway.total == 1 -> getString(R.string.download_line_underway_one)
            else -> words.underway(underway.position, underway.total)
        }
        val text = underway?.let {
            if (it.percent == null) it.title else words.join(it.title, words.percent(it.percent))
        }
        return NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openDownloads())
            .addAction(
                0,
                getString(R.string.download_stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, DownloadService::class.java).setAction(STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    private fun openDownloads(): PendingIntent =
        PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun goForeground(notification: Notification) {
        ServiceCompat.startForeground(this, ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun show(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(ONGOING_ID, notification)
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return CHANNEL
    }

    /**
     * The screen going off is what a download has to survive. Without these the phone sleeps
     * the processor and powers the radio down a minute later, and the socket stalls until it
     * times out.
     */
    private fun holdAwake() {
        if (wake?.isHeld != true) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jimeikin:download")
                .apply { acquire(WAKE_LIMIT_MS) }
        }
        if (wifi?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jimeikin:download")
                ?.apply { acquire() }
        }
    }

    private fun letGo() {
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        wake = null
        wifi = null
    }

    /** Android 15 caps how long a data-sync service may run in a day; past it, stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        queue.cancelAll()
    }

    override fun onDestroy() {
        letGo()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val STOP = "com.wanderwildwood.jimeikin.STOP_DOWNLOADS"
        private const val CHANNEL = "downloads"
        private const val ONGOING_ID = 21
        private const val DONE_ID = 22

        /** Long enough for any run over a slow home network; a backstop, not a schedule. */
        private const val WAKE_LIMIT_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }.onFailure {
                // Asked from somewhere Android will not start a foreground service from. The
                // queue still runs while the app is open; it only loses the cover of the service.
                android.util.Log.w("DownloadService", "could not start", it)
            }
        }
    }
}

/** The line's words, from strings.xml. */
class DownloadWords(private val res: Resources) : DownloadLineWords {
    override fun underway(position: Int, total: Int) = res.getString(R.string.download_line_underway, position, total)
    override fun underwayOne() = res.getString(R.string.download_line_underway_one)
    override fun percent(percent: Int) = res.getString(R.string.player_downloads_percent, percent)
    override fun downloaded(count: Int) = res.getQuantityString(R.plurals.main_songs_on_phone_now, count, count)
    override fun failed(count: Int) = res.getQuantityString(R.plurals.download_line_failed, count, count)
    override fun downloadedOne(title: String) = res.getString(R.string.main_song_on_phone_now, title)
    override fun failedOne(title: String) = res.getString(R.string.download_line_failed_one, title)
    override fun join(first: String, second: String) = res.getString(R.string.download_line_join, first, second)
}
