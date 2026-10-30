package dev.omalauncher.pp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.omalauncher.pp.linux.AgentPhase
import dev.omalauncher.pp.linux.AgentSession

/**
 * One notification per agent session in the "Coding agents" channel, updated in place: tapping it opens the
 * session's terminal, and a permission request gets Allow and Deny. Only news alerts (done, waiting for you, a
 * question); a tool starting updates it silently. The channel's importance, sound and Do Not Disturb are the
 * user's, in Android's settings.
 */
class AgentNotifications(
    private val context: Context,
) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var channel = false

    fun post(
        session: AgentSession,
        agentName: String,
        alert: Boolean,
    ) {
        if (!nm.areNotificationsEnabled()) return
        if (!channel) {
            channel = true
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.agent_status_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.agent_status_channel_description)
                },
            )
        }
        val folder = session.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        val title = if (folder == null) agentName else context.getString(R.string.agent_status_title, agentName, folder)
        val text = text(session)
        val id = session.id
        val builder =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setWhen(session.since)
                .setShowWhen(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        id,
                        Intent(context, AgentSessionActivity::class.java).putExtra(EXTRA_SESSION, id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).setDeleteIntent(broadcast(ACTION_DISMISS, id, null))
                .setAutoCancel(false)
                // Notification.Builder has no setSilent before Android 12's NotificationCompat: a member of a group
                // that alerts for its summary only, with no summary posted, never alerts.
                .setGroup(GROUP)
                .setGroupAlertBehavior(if (alert) Notification.GROUP_ALERT_ALL else Notification.GROUP_ALERT_SUMMARY)
                .setOnlyAlertOnce(!alert)
        logo(session.agent)?.let { builder.setLargeIcon(Icon.createWithResource(context, it)) }
        session.request?.let { request ->
            builder.setCategory(Notification.CATEGORY_REMINDER)
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.agent_status_allow), broadcast(ACTION_ALLOW, id, request.id)).build())
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.agent_status_deny), broadcast(ACTION_DENY, id, request.id)).build())
        }
        nm.notify(TAG, id, builder.build())
    }

    fun cancel(id: Int) = nm.cancel(TAG, id)

    /** Sessions live as long as the process, like the guest: what an earlier one left in the shade answers nothing. */
    fun cancelAll() = runCatching { nm.activeNotifications.filter { it.tag == TAG }.forEach { nm.cancel(TAG, it.id) } }

    private fun text(session: AgentSession): String {
        val word =
            when (session.phase) {
                AgentPhase.Running -> R.string.agent_status_running
                AgentPhase.Input -> R.string.agent_status_input
                AgentPhase.Permission -> if (session.request != null) R.string.agent_status_permission else R.string.agent_status_permission_terminal
                AgentPhase.Done -> R.string.agent_status_done
            }
        val summary = session.summary ?: return context.getString(word)
        return context.getString(R.string.agent_status_line, context.getString(word), summary)
    }

    /** Each action its own PendingIntent, by session and kind: one session's Allow never carries another's request. */
    private fun broadcast(
        action: String,
        session: Int,
        request: String?,
    ): PendingIntent {
        val intent =
            Intent(context, AgentStatusReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_SESSION, session)
                .putExtra(EXTRA_REQUEST, request)
        val code = session * 4 + ACTIONS.indexOf(action)
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The agent's logo, as its window's Recents card shows it (drawable/agent_<bin>). */
    @SuppressLint("DiscouragedApi") // The agents' logos are named after their bins, as the manifest's window aliases use them.
    private fun logo(bin: String): Int? =
        context.resources.getIdentifier("agent_" + bin.replace('-', '_'), "drawable", context.packageName).takeIf { it != 0 }

    companion object {
        const val CHANNEL = "agents"
        private const val GROUP = "agents"

        /** Its own namespace of ids: widgets' notify() counts up from 1000 without one. */
        private const val TAG = "agent"
        const val EXTRA_SESSION = "session"
        const val EXTRA_REQUEST = "request"
        const val ACTION_ALLOW = "dev.omalauncher.pp.agent.ALLOW"
        const val ACTION_DENY = "dev.omalauncher.pp.agent.DENY"
        const val ACTION_DISMISS = "dev.omalauncher.pp.agent.DISMISS"
        private val ACTIONS = listOf(ACTION_ALLOW, ACTION_DENY, ACTION_DISMISS)
    }
}
