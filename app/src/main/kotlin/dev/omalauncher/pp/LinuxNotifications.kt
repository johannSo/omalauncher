package dev.omalauncher.pp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.omalauncher.pp.linux.LinuxApp
import dev.omalauncher.pp.linux.LinuxNotification
import dev.omalauncher.pp.linux.NotificationDisplay
import dev.omalauncher.pp.linux.NotificationServer
import dev.omalauncher.pp.linux.NotifyMarkup
import dev.omalauncher.pp.linux.Proot
import dev.omalauncher.pp.linux.Urgency
import dev.omalauncher.pp.linux.findNotifyingApp
import dev.omalauncher.pp.linux.notifyImagePath
import dev.omalauncher.pp.linux.resolveLinuxIcon
import dev.omalauncher.pp.ui.decodeIcon

/**
 * Linux apps' notifications as Android's: what [NotificationServer] receives on the guest's session
 * bus shows in the shade, under the sending app's name and icon. Its buttons are the actions, a swipe
 * dismisses it, and a tap brings the app's window back (or opens the app). They live as long as Linux
 * runs: shutting it down takes them away, and ones left from a process that died go at start.
 */
class LinuxNotifications(
    private val context: Context,
    private val proot: Proot,
    private val apps: LinuxAppCatalog,
    /** Opens a Linux app, or brings its window to the front. */
    private val open: (LinuxApp) -> Unit,
) : NotificationDisplay {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val server = NotificationServer(proot, this, BuildConfig.VERSION_NAME)

    init {
        // Their apps, and the bus their actions went to, died with the last process.
        runCatching { nm.activeNotifications.filter { it.tag == TAG }.forEach { nm.cancel(TAG, it.id) } }
    }

    /** Starts the guest's session bus and shows its notifications, or resumes after [stop]. */
    fun start() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL, context.getString(R.string.linux_notifications_channel), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_URGENT, context.getString(R.string.linux_notifications_channel_urgent), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_QUIET, context.getString(R.string.linux_notifications_channel_quiet), NotificationManager.IMPORTANCE_LOW),
            ),
        )
        server.start()
    }

    /** Linux is shutting down or being removed: its notifications go, and the bus isn't restarted. */
    fun stop() = server.stop()

    /** The bus's config in the guest. */
    fun managed() = server.managed()

    override fun show(
        id: Int,
        notification: LinuxNotification,
        update: Boolean,
    ) {
        val n = notification
        val app = findNotifyingApp(apps.apps.toList(), n.desktopEntry, n.appName)
        val body = styled(NotifyMarkup.parse(n.body))
        val builder =
            Notification
                .Builder(context, channel(n.urgency))
                // The app's silhouette in the status bar, like the drawer's monochrome icons.
                .setSmallIcon(app?.let { apps.masks[it.id] }?.let { Icon.createWithBitmap(it.asAndroidBitmap()) } ?: Icon.createWithResource(context, R.drawable.ic_notification))
                .setContentTitle(n.summary)
                .setSubText(app?.name ?: n.appName.takeIf { it.isNotBlank() })
                .setShowWhen(true)
                .setAutoCancel(!n.resident)
                // An update in place (a progress, a volume) doesn't sound again.
                .setOnlyAlertOnce(update)
                .setContentIntent(PendingIntent.getActivity(context, 0, intent(TapActivity::class.java, id, null), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(broadcast(ACTION_DISMISS, id, null))
        if (body.isNotEmpty()) builder.setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
        largeIcon(n, app)?.let(builder::setLargeIcon)
        n.progress?.let { builder.setProgress(100, it, false) }
        n.lifetimeMs?.let(builder::setTimeoutAfter)
        if (n.urgency == Urgency.Critical) builder.setCategory(Notification.CATEGORY_ALARM)
        for (action in n.buttons) {
            builder.addAction(Notification.Action.Builder(null as Icon?, action.label, broadcast(ACTION_INVOKE, id, action.key)).build())
        }
        // Without the permission (Android 13+, asked when Linux is installed) it doesn't show.
        runCatching { nm.notify(TAG, id, builder.build()) }.onFailure { Log.w(TAG, "notification $id not shown", it) }
    }

    override fun hide(id: Int) = nm.cancel(TAG, id)

    /** A tap: its default action, if it has one, and the app's window. */
    fun tapped(id: Int) {
        val n = server.invoke(id, LinuxNotification.DEFAULT_ACTION) ?: return
        findNotifyingApp(apps.apps.toList(), n.desktopEntry, n.appName)?.let(open)
    }

    fun invoked(
        id: Int,
        key: String,
    ) {
        server.invoke(id, key) ?: hide(id)
    }

    fun dismissed(id: Int) = server.dismissed(id)

    private fun channel(urgency: Urgency) =
        when (urgency) {
            Urgency.Low -> CHANNEL_QUIET
            Urgency.Normal -> CHANNEL
            Urgency.Critical -> CHANNEL_URGENT
        }

    /** The image the notification sends (pixels, a file), else its app_icon, else its app's drawer icon. */
    private fun largeIcon(
        n: LinuxNotification,
        app: LinuxApp?,
    ): Icon? {
        val size = (64 * context.resources.displayMetrics.density).toInt()
        val bitmap =
            n.image?.let { img -> img.argb()?.let { Bitmap.createBitmap(it, img.width, img.height, Bitmap.Config.ARGB_8888) } }
                ?: n.imagePath?.let { image(it, size) }
                ?: n.appIcon.takeIf { it.isNotBlank() }?.let { image(it, size) }
                ?: app?.let { apps.icons[it.id]?.asAndroidBitmap() }
        return bitmap?.let(Icon::createWithBitmap)
    }

    /** A guest image file, or an icon by name from the guest's icon theme. */
    private fun image(
        value: String,
        size: Int,
    ): Bitmap? {
        val file = notifyImagePath(value)?.let(proot::guestFile) ?: resolveLinuxIcon(proot.rootfs, value)
        return file?.takeIf { it.isFile }?.let { runCatching { decodeIcon(it, size) }.getOrNull() }
    }

    private fun intent(
        target: Class<*>,
        id: Int,
        key: String?,
    ) = Intent(context, target)
        // Distinct data per notification and action, so their PendingIntents don't replace each other.
        .setData(Uri.Builder().scheme("omarchy").authority("notification").appendPath(id.toString()).apply { key?.let(::appendPath) }.build())
        .putExtra(EXTRA_ID, id)
        .putExtra(EXTRA_KEY, key)

    private fun broadcast(
        action: String,
        id: Int,
        key: String?,
    ) = PendingIntent.getBroadcast(
        context,
        0,
        intent(Receiver::class.java, id, key).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A swipe or an action button. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            val notifications = (context.applicationContext as App).runtime.notifications
            val id = intent.getIntExtra(EXTRA_ID, 0)
            when (intent.action) {
                ACTION_DISMISS -> notifications.dismissed(id)
                ACTION_INVOKE -> notifications.invoked(id, intent.getStringExtra(EXTRA_KEY) ?: return)
            }
        }
    }

    /**
     * A tap. An activity rather than a broadcast: Android lets only an activity a notification opened
     * bring another task to the front. It shows nothing and goes at once.
     */
    class TapActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            (application as App).runtime.notifications.tapped(intent.getIntExtra(EXTRA_ID, 0))
            finish()
        }
    }

    companion object {
        private const val TAG = "linux"
        private const val CHANNEL = "linux"
        private const val CHANNEL_URGENT = "linux_urgent"
        private const val CHANNEL_QUIET = "linux_quiet"
        private const val ACTION_DISMISS = "dev.omalauncher.pp.LINUX_NOTIFICATION_DISMISSED"
        private const val ACTION_INVOKE = "dev.omalauncher.pp.LINUX_NOTIFICATION_ACTION"
        private const val EXTRA_ID = "id"
        private const val EXTRA_KEY = "key"

        /** Body markup as Android text: bold, italic, underline; links underlined, as they can't be opened from there. */
        fun styled(markup: NotifyMarkup.Styled): CharSequence {
            if (markup.spans.isEmpty()) return markup.text
            val s = SpannableString(markup.text)
            for (span in markup.spans) {
                val what: Any =
                    when (span.style) {
                        NotifyMarkup.Style.Bold -> StyleSpan(Typeface.BOLD)
                        NotifyMarkup.Style.Italic -> StyleSpan(Typeface.ITALIC)
                        NotifyMarkup.Style.Underline, is NotifyMarkup.Style.Link -> UnderlineSpan()
                    }
                s.setSpan(what, span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return s
        }
    }
}
