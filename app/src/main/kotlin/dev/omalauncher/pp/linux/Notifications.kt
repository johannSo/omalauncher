package dev.omalauncher.pp.linux

/** The urgency hint: low is quiet, critical stays until dismissed and pops up. */
enum class Urgency { Low, Normal, Critical }

/** One of a notification's actions: [key] comes back in ActionInvoked, [label] is the button. */
data class NotifyAction(
    val key: String,
    val label: String,
)

/** The image-data hint, (iiibiiay): raw RGB or RGBA rows. */
class NotifyImage(
    val width: Int,
    val height: Int,
    val rowstride: Int,
    val hasAlpha: Boolean,
    val bitsPerSample: Int,
    val channels: Int,
    val data: ByteArray,
) {
    /** The pixels as ARGB, for Bitmap.createBitmap; null if the data doesn't add up or the image is too big. */
    fun argb(): IntArray? {
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS || bitsPerSample != 8) return null
        if (channels != (if (hasAlpha) 4 else 3) || rowstride < width * channels) return null
        if (data.size < (height - 1).toLong() * rowstride + width * channels) return null
        val out = IntArray(width * height)
        for (y in 0 until height) {
            var p = y * rowstride
            for (x in 0 until width) {
                val r = data[p].toInt() and 0xff
                val g = data[p + 1].toInt() and 0xff
                val b = data[p + 2].toInt() and 0xff
                val a = if (hasAlpha) data[p + 3].toInt() and 0xff else 0xff
                out[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                p += channels
            }
        }
        return out
    }

    companion object {
        private const val MAX_PIXELS = 1024 * 1024

        fun from(value: Any?): NotifyImage? {
            val s = value as? List<*> ?: return null
            if (s.size != 7) return null
            return NotifyImage(
                (s[0] as? Number)?.toInt() ?: return null,
                (s[1] as? Number)?.toInt() ?: return null,
                (s[2] as? Number)?.toInt() ?: return null,
                s[3] as? Boolean ?: return null,
                (s[4] as? Number)?.toInt() ?: return null,
                (s[5] as? Number)?.toInt() ?: return null,
                s[6] as? ByteArray ?: return null,
            )
        }
    }
}

/**
 * A notification as a Linux app sent it with Notify (org.freedesktop.Notifications, spec 1.2),
 * with the hints the launcher shows.
 */
data class LinuxNotification(
    val appName: String,
    /** A theme icon name, a path or a file:// URI; may be empty. */
    val appIcon: String,
    /** Plain text. */
    val summary: String,
    /** Markup; see [NotifyMarkup]. */
    val body: String,
    val actions: List<NotifyAction>,
    /** Milliseconds; -1 leaves it to us, 0 never expires. */
    val expireTimeout: Int,
    val urgency: Urgency = Urgency.Normal,
    /** The sending app's desktop file id, without .desktop. */
    val desktopEntry: String? = null,
    val image: NotifyImage? = null,
    val imagePath: String? = null,
    /** Stays after one of its actions was invoked. */
    val resident: Boolean = false,
    /** Not to be kept around: one without a timeout of its own goes after [TRANSIENT_MS]. */
    val transient: Boolean = false,
    /** A progress bar, 0..100. */
    val progress: Int? = null,
    /** dunst's and Canonical's stack tag: a notification with the same app and tag replaces the last one. */
    val stackTag: String? = null,
) {
    /** Whether a tap on the notification itself has an action to invoke. */
    val hasDefaultAction get() = actions.any { it.key == DEFAULT_ACTION }

    /** The actions shown as buttons: Android shows at most three, and "default" is the tap. */
    val buttons get() = actions.filter { it.key != DEFAULT_ACTION && it.label.isNotBlank() }.take(3)

    /**
     * How long it shows before it closes by itself, in milliseconds; null until it's dismissed. Without
     * a timeout of its own it stays, as Android's own do. Critical ones always stay, as the spec asks.
     */
    val lifetimeMs: Long?
        get() =
            when {
                urgency == Urgency.Critical -> null
                expireTimeout > 0 -> expireTimeout.toLong()
                expireTimeout == 0 -> null
                transient -> TRANSIENT_MS
                else -> null
            }

    companion object {
        const val DEFAULT_ACTION = "default"
        const val TRANSIENT_MS = 10_000L

        /** The D-Bus signature of Notify's arguments. */
        const val NOTIFY_SIGNATURE = "susssasa{sv}i"

        /**
         * Reads Notify's arguments (app_name, replaces_id, app_icon, summary, body, actions, hints,
         * expire_timeout), as [DBus.Reader] returns them for [NOTIFY_SIGNATURE]; returns replaces_id too.
         */
        fun fromNotify(args: List<Any?>): Pair<Int, LinuxNotification> {
            require(args.size == 8) { "Notify takes 8 arguments" }
            val flat = (args[5] as List<*>).map { it as String }
            val hints = (args[6] as Map<*, *>).entries.associate { (k, v) -> k as String to (v as? DBus.Variant)?.value }
            fun string(vararg keys: String) = keys.firstNotNullOfOrNull { hints[it] as? String }?.takeIf { it.isNotEmpty() }
            fun flag(key: String) =
                when (val v = hints[key]) {
                    is Boolean -> v
                    is Number -> v.toInt() != 0
                    else -> false
                }
            val notification =
                LinuxNotification(
                    appName = args[0] as String,
                    appIcon = args[2] as String,
                    summary = args[3] as String,
                    body = args[4] as String,
                    actions = flat.chunked(2).filter { it.size == 2 }.map { NotifyAction(it[0], it[1]) },
                    expireTimeout = (args[7] as Number).toInt(),
                    urgency =
                        when ((hints["urgency"] as? Number)?.toInt()) {
                            0 -> Urgency.Low
                            2 -> Urgency.Critical
                            else -> Urgency.Normal
                        },
                    desktopEntry = string("desktop-entry")?.removeSuffix(".desktop"),
                    // image_data and icon_data are the names of spec 1.1 and 1.0.
                    image = listOf("image-data", "image_data", "icon_data").firstNotNullOfOrNull { NotifyImage.from(hints[it]) },
                    imagePath = string("image-path", "image_path"),
                    resident = flag("resident"),
                    transient = flag("transient"),
                    progress = (hints["value"] as? Number)?.toInt()?.coerceIn(0, 100),
                    stackTag = string("x-dunst-stack-tag", "x-canonical-private-synchronous"),
                )
            return ((args[1] as Number).toLong().toInt()) to notification
        }
    }
}

/** Why a notification closed, as NotificationClosed reports it. */
object CloseReason {
    const val EXPIRED = 1
    const val DISMISSED = 2
    const val CLOSED = 3
}

/**
 * The app that sent a notification: by its desktop-entry hint, else by app_name, which is usually
 * the app's name ("Firefox") or its binary ("firefox"). Null for scripts and tools without an entry.
 */
fun findNotifyingApp(
    apps: List<LinuxApp>,
    desktopEntry: String?,
    appName: String,
): LinuxApp? {
    if (!desktopEntry.isNullOrEmpty()) {
        apps.firstOrNull { it.id.equals(desktopEntry, ignoreCase = true) }?.let { return it }
        apps.firstOrNull { it.matches(desktopEntry) }?.let { return it }
    }
    if (appName.isBlank()) return null
    return apps.firstOrNull { it.name.equals(appName, ignoreCase = true) } ?: apps.firstOrNull { it.matches(appName) }
}

/**
 * A guest path to an image from a notification's image-path or app_icon: a path or a file:// URI.
 * Null for an icon name, which [resolveLinuxIcon] looks up.
 */
fun notifyImagePath(value: String): String? =
    when {
        // Unescaped, as some apps send it, it isn't a valid URI.
        value.startsWith("file://") -> runCatching { java.net.URI(value).path }.getOrNull() ?: value.removePrefix("file://")
        value.startsWith("/") -> value
        else -> null
    }

/**
 * The markup a notification body may have (spec: b, i, u, a href, img alt), as plain text and style
 * ranges. Pango tags some apps send anyway (span, tt, s, …) are dropped and their text kept; anything
 * else that looks like a tag, such as an unescaped "List<String>", stays as written.
 */
object NotifyMarkup {
    sealed interface Style {
        data object Bold : Style

        data object Italic : Style

        data object Underline : Style

        data class Link(
            val href: String,
        ) : Style
    }

    data class Span(
        val start: Int,
        val end: Int,
        val style: Style,
    )

    data class Styled(
        val text: String,
        val spans: List<Span>,
    )

    private val TAG = Regex("""<\s*(/?)\s*([a-zA-Z]+)((?:\s+[^<>]*?)?)\s*(/?)\s*>""")
    private val ATTR = Regex("""([a-zA-Z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos);""")
    private val DROPPED = setOf("span", "tt", "s", "big", "small", "sub", "sup", "p", "font")

    fun parse(markup: String): Styled {
        val text = StringBuilder()
        val spans = ArrayList<Span>()
        // Open tags with where their text starts, innermost last.
        val open = ArrayList<Triple<String, Int, Style?>>()
        var i = 0
        while (i < markup.length) {
            val c = markup[i]
            if (c == '<') {
                val m = TAG.matchAt(markup, i)
                val name = m?.groupValues?.get(2)?.lowercase()
                if (m != null && name != null && (name in STYLED || name in DROPPED || name == "img" || name == "br")) {
                    val closing = m.groupValues[1].isNotEmpty()
                    val selfClosing = m.groupValues[4].isNotEmpty()
                    val attrs = ATTR.findAll(m.groupValues[3]).associate { it.groupValues[1].lowercase() to entities(it.groupValues[2] + it.groupValues[3]) }
                    when {
                        name == "br" -> text.append('\n')
                        name == "img" -> if (!closing) text.append(attrs["alt"].orEmpty())
                        closing -> {
                            val at = open.indexOfLast { it.first == name }
                            if (at >= 0) {
                                val (_, start, style) = open.removeAt(at)
                                if (style != null && text.length > start) spans += Span(start, text.length, style)
                            }
                        }
                        !selfClosing -> open += Triple(name, text.length, style(name, attrs))
                    }
                    i = m.range.last + 1
                    continue
                }
            } else if (c == '&') {
                val m = ENTITY.matchAt(markup, i)
                if (m != null) {
                    text.append(entities(m.value))
                    i = m.range.last + 1
                    continue
                }
            }
            text.append(c)
            i++
        }
        // Unclosed tags style the rest.
        for ((_, start, style) in open) if (style != null && text.length > start) spans += Span(start, text.length, style)
        return Styled(text.toString(), spans.sortedBy { it.start })
    }

    private val STYLED = setOf("b", "i", "u", "a")

    private fun style(
        name: String,
        attrs: Map<String, String>,
    ): Style? =
        when (name) {
            "b" -> Style.Bold
            "i" -> Style.Italic
            "u" -> Style.Underline
            "a" -> attrs["href"]?.let { Style.Link(it) }
            else -> null
        }

    private fun entities(s: String): String =
        ENTITY.replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (e[1] == 'x' || e[1] == 'X') e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (code == null || code !in 1..0x10FFFF || code in 0xD800..0xDFFF) m.value else String(Character.toChars(code))
                }
            }
        }
}
