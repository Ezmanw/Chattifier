package com.ethan.localagent.hf

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ethan.localagent.app
import com.ethan.localagent.util.Notifications
import kotlinx.coroutines.launch

/** Keeps the process in the foreground while any model is downloading, with a progress notification. */
class DownloadService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(Notifications.ID_DOWNLOADS, build("Starting download…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        lifecycleScope.launch {
            app.downloads.tasks.collect { tasks ->
                val active = tasks.filter { it.active }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val running = active.firstNotNullOfOrNull { t -> (t.status as? DownloadStatus.Running)?.let { t to it } }
                val title = if (active.size > 1) "Downloading ${active.size} models" else "Downloading ${active.first().title}"
                val n = if (running != null) {
                    val (_, r) = running
                    val pct = if (r.total > 0) (r.bytes * 100 / r.total).toInt() else 0
                    build(
                        "$title · ${Formatter.formatShortFileSize(this@DownloadService, r.bytes)} of " +
                            Formatter.formatShortFileSize(this@DownloadService, r.total),
                        pct, 100,
                    )
                } else build(title, 0, 0)
                getSystemService(android.app.NotificationManager::class.java).notify(Notifications.ID_DOWNLOADS, n)
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, progress: Int, max: Int): Notification =
        NotificationCompat.Builder(this, Notifications.DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Chattifier")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, max == 0)
            .setContentIntent(Notifications.openApp(this, "models"))
            .build()
}
