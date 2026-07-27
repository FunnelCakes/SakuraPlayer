package com.sakura.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.sakura.player.data.SettingsPrefs
import com.sakura.player.download.DownloadManager
import com.sakura.player.download.DownloadNotif
import kotlinx.coroutines.*

class AnimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotif.createChannel(this)
        DownloadManager.setCallback { task ->
            when (task.status) {
                "downloading" -> DownloadNotif.showProgress(this, task)
                "completed" -> {
                    DownloadNotif.showComplete(this, task)
                    DownloadNotif.cancel(this, task.id.hashCode())
                }
                "failed", "cancelled" -> DownloadNotif.cancel(this, task.id.hashCode())
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

        // Acquire wake lock to keep CPU running during downloads
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sakura:download").apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.release()
        super.onDestroy()
    }
}
