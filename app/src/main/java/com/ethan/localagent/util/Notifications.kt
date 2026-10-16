package com.ethan.localagent.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ethan.localagent.MainActivity

object Notifications {
    const val DOWNLOADS = "downloads"
    const val AGENT = "agent"
    const val APPROVALS = "approvals"
    const val SETUP = "setup"

    const val ID_DOWNLOADS = 10
    const val ID_AGENT = 20
    const val ID_APPROVAL = 21
    const val ID_PAIRING = 30

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(DOWNLOADS, "Model downloads", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(AGENT, "Code agent running", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(APPROVALS, "Code agent needs you", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(SETUP, "Setup and pairing", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    fun openApp(context: Context, route: String? = null): PendingIntent =
        PendingIntent.getActivity(
            context,
            route.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
