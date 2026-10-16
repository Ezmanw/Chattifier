package com.ethan.localagent.agent

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ethan.localagent.R
import com.ethan.localagent.app
import com.ethan.localagent.util.Notifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the agent alive with the screen off: a foreground service plus a partial wake lock for
 * exactly as long as a task runs.
 */
class AgentService : LifecycleService() {
    private var wake: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(Notifications.ID_AGENT, ongoing("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Chattifier:agent")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        }
        lifecycleScope.launch {
            val agent = app.agent
            // Give the controller a moment to flip to running before deciding we're done.
            delay(500)
            while (agent.state.value.running) {
                val s = agent.state.value
                val mins = (System.currentTimeMillis() - s.startedAt) / 60_000
                val last = s.events.lastOrNull { it is AgentEvent.Tool } as? AgentEvent.Tool
                val text = when {
                    s.approval != null -> "Waiting for you: ${s.approval.summary}"
                    last != null -> "${last.summary} · ${mins}m"
                    else -> "Thinking · ${mins}m"
                }
                getSystemService(NotificationManager::class.java).notify(Notifications.ID_AGENT, ongoing(text))
                delay(2_000)
            }
            agent.state.first { !it.running }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun ongoing(text: String) = NotificationCompat.Builder(this, Notifications.AGENT)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Code agent is working")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(Notifications.openApp(this, "code"))
        .addAction(0, "Stop", AgentActionReceiver.intent(this, AgentActionReceiver.STOP))
        .build()
}

object AgentNotifications {
    fun approval(context: Context, a: Approval) {
        val n = NotificationCompat.Builder(context, Notifications.APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (a.command != null) "Allow this command?" else "Allow this change?")
            .setContentText(a.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.detail.take(600)))
            .setContentIntent(Notifications.openApp(context, "code"))
            .setAutoCancel(true)
            .addAction(0, "Deny", AgentActionReceiver.intent(context, AgentActionReceiver.DENY))
            .addAction(0, "Allow", AgentActionReceiver.intent(context, AgentActionReceiver.ALLOW))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(Notifications.ID_APPROVAL, n)
    }

    fun clearApproval(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_APPROVAL)

    fun done(context: Context, summary: String) {
        val n = NotificationCompat.Builder(context, Notifications.APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Code agent finished")
            .setContentText(summary.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(800)))
            .setContentIntent(Notifications.openApp(context, "code"))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(Notifications.ID_APPROVAL + 1, n)
    }
}

class AgentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val agent = context.app.agent
        when (intent.action) {
            ALLOW -> agent.decide(Decision.ALLOW)
            DENY -> agent.decide(Decision.DENY)
            STOP -> agent.stop()
        }
    }

    companion object {
        const val ALLOW = "com.ethan.localagent.ALLOW"
        const val DENY = "com.ethan.localagent.DENY"
        const val STOP = "com.ethan.localagent.STOP"

        fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, AgentActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
