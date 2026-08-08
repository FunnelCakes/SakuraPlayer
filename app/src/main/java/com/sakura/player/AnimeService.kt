package com.sakura.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.sakura.player.download.DownloadManager
import com.sakura.player.download.DownloadNotif
import kotlinx.coroutines.*

class AnimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    // Monotonic timestamp of the last time the wake lock was (re)armed, used to
    // throttle re-arming so a high-frequency progress callback doesn't release +
    // re-acquire the lock on every tick.
    private var lastWakeLockArmMs = 0L

    override fun onCreate() {
        super.onCreate()
        DownloadNotif.createChannel(this)
        DownloadManager.setCallback { task ->
            when (task.status) {
                "downloading" -> {
                    DownloadNotif.showProgress(this, task)
                    rearmWakeLock()
                }
                "completed" -> {
                    DownloadNotif.showComplete(this, task)
                    DownloadNotif.cancel(this, task.id.hashCode())
                    onQueueChanged()
                }
                "failed", "cancelled" -> {
                    DownloadNotif.cancel(this, task.id.hashCode())
                    onQueueChanged()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: "start"

        when (action) {
            "start" -> startForeground()
            "stop" -> stopSelf()
        }

        return START_STICKY
    }

    private fun startForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "download_channel")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("樱花动漫")
            .setContentText("下载管理运行中")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        // Keep a sliding 10-minute CPU wake lock for as long as the queue has work.
        // rearmWakeLock() releases any previously-held lock first, so repeated
        // onStartCommand("start") calls can never leak a wake lock.
        if (isDownloadActive()) rearmWakeLock()
    }

    /**
     * Re-arm the partial wake lock for another 10-minute window. Called on every
     * download-progress callback while the queue is non-empty, so the CPU stays
     * awake for the WHOLE batch (not just the first 10 minutes). This is what keeps
     * the process from being frozen by EMUI fastHibernation when the app is
     * backgrounded on a long multi-episode batch.
     *
     * Re-arming is throttled to once per minute to avoid churn from high-frequency
     * progress callbacks, but if the lock has already expired (isHeld == false) it
     * is re-acquired immediately regardless of the throttle.
     */
    private fun rearmWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sakura:download").also {
            wakeLock = it
        }
        val now = SystemClock.elapsedRealtime()
        if (wl.isHeld) {
            if (now - lastWakeLockArmMs < 60_000L) return // recently re-armed, skip
            wl.release()
        }
        wl.acquire(10 * 60 * 1000L)
        lastWakeLockArmMs = now
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    /**
     * Called whenever a task reaches a terminal state (completed / failed /
     * cancelled). If no downloads remain in the queue, drop the wake lock and stop
     * the service so the persistent foreground notification goes away. If more work
     * is still pending, re-arm the wake lock for the next batch.
     */
    private fun onQueueChanged() {
        if (isDownloadActive()) {
            rearmWakeLock()
        } else {
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun isDownloadActive(): Boolean {
        return DownloadManager.getAllStatus()
            .any { it.status == "queued" || it.status == "downloading" }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
