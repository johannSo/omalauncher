package dev.omalauncher.pp.theme

/**
 * What a gesture on home does, as style.toml writes it, so agents can set one as easily as the gestures screen does:
 * a word for the launcher's own ("drawer", "lock") or a prefix and what it's for ("panel:controls.owl",
 * "app:org.mozilla.firefox", "linux:notify-send hi", "agent:what's on today?"). [encoded] reads back as the same
 * action, and a value from before actions (a bare panel file name, "drawer.owl") still reads as its panel.
 */
sealed interface GestureAction {
    /** The value style.toml keeps for it. */
    val encoded: String

    /** The gesture is home's own again: a swipe up, say, doesn't open the drawer. */
    data object None : GestureAction {
        override val encoded = "none"
    }

    /** The app drawer: the panel the drawer shows (gestures.swipe-up's, else drawer.owl), above the bar. */
    data object Drawer : GestureAction {
        override val encoded = "drawer"
    }

    /** Android's notification shade. */
    data object Shade : GestureAction {
        override val encoded = "notification-shade"
    }

    /** Android's quick settings, the shade pulled all the way down. */
    data object QuickSettings : GestureAction {
        override val encoded = "quick-settings"
    }

    /** Settings › pages, to arrange home's pages. */
    data object Pages : GestureAction {
        override val encoded = "pages"
    }

    /** Locks the phone, its screen off, through the launcher's accessibility service. */
    data object Lock : GestureAction {
        override val encoded = "lock"
    }

    /** Android's recent apps, through the launcher's accessibility service. */
    data object Recents : GestureAction {
        override val encoded = "recents"
    }

    /** A panel's [file] in ~/.config/omalauncher/widgets: swiping up, it's what the drawer shows; otherwise it comes down from the top. */
    data class OpenPanel(
        val file: String,
    ) : GestureAction {
        override val encoded get() = "$PANEL$file"
    }

    /** An Android app: [component] as ComponentName flattens it, "package/activity", or a bare package for its launcher activity. */
    data class OpenApp(
        val component: String,
    ) : GestureAction {
        override val encoded get() = "$APP$component"

        val packageName get() = component.substringBefore('/')

        /** The activity, as ComponentName takes it (".Main" short for the package's own); null for the package's launcher activity. */
        val activity get() = component.substringAfter('/', "").ifEmpty { null }
    }

    /** A Linux app by its desktop file's id (firefox.desktop is "firefox"), opened as the drawer opens it. */
    data class OpenLinuxApp(
        val id: String,
    ) : GestureAction {
        override val encoded get() = "$LINUX_APP$id"
    }

    /** A shell [command] run in Linux without a window of its own; a GUI app it starts gets one. */
    data class RunLinux(
        val command: String,
    ) : GestureAction {
        override val encoded get() = "$LINUX$command"
    }

    /** A shell [command] in a terminal of its own, which closes as it ends ("btop", or "make; exec bash" to stay). */
    data class RunInTerminal(
        val command: String,
    ) : GestureAction {
        override val encoded get() = "$TERMINAL$command"
    }

    /** [prompt] sent to the default coding agent, as the bar sends one. */
    data class AskAgent(
        val prompt: String,
    ) : GestureAction {
        override val encoded get() = "$AGENT$prompt"
    }

    companion object {
        const val PANEL = "panel:"
        const val APP = "app:"
        const val LINUX_APP = "linux-app:"
        const val LINUX = "linux:"
        const val TERMINAL = "terminal:"
        const val AGENT = "agent:"

        /** What [parse] says a bad value should be instead, for errors.txt. */
        const val TAKES =
            "takes \"none\", \"drawer\", \"notification-shade\", \"quick-settings\", \"pages\", \"lock\", \"recents\", " +
                "\"panel:<file>.owl\", \"app:<package>[/<activity>]\", \"linux-app:<desktop id>\", \"linux:<command>\", " +
                "\"terminal:<command>\" or \"agent:<prompt>\""

        private val WORDS: Map<String, GestureAction> =
            listOf(None, Drawer, Shade, QuickSettings, Pages, Lock, Recents).associateBy { it.encoded } +
                // What one might write for them, read as the same.
                mapOf("nothing" to None, "shade" to Shade, "notifications" to Shade, "lock-screen" to Lock, "recent-apps" to Recents)

        /** "org.example.app": dot-separated names, as Android's packages are. */
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")

        /** [text] as an action, or [IllegalArgumentException] saying what it takes. Words and prefixes read in any case; what follows a prefix is kept as written. */
        fun parse(text: String): GestureAction {
            val value = text.trim()
            WORDS[value.lowercase()]?.let { return it }
            fun after(prefix: String) = value.takeIf { it.startsWith(prefix, ignoreCase = true) }?.substring(prefix.length)?.trim()
            after(PANEL)?.let { file ->
                require(file.isNotEmpty() && '/' !in file) { "$PANEL takes a panel's file name in ~/.config/omalauncher/widgets" }
                return OpenPanel(if (file.endsWith(".owl")) file else "$file.owl")
            }
            after(APP)?.let { component ->
                val pkg = component.substringBefore('/')
                val activity = component.substringAfter('/', "")
                require(PACKAGE.matches(pkg) && (activity.isNotEmpty() || '/' !in component) && ' ' !in activity) {
                    "$APP takes an Android app's package, or package/activity"
                }
                return OpenApp(component)
            }
            after(LINUX_APP)?.let { id ->
                require(id.isNotEmpty()) { "$LINUX_APP takes a desktop file's id, as firefox.desktop is \"firefox\"" }
                return OpenLinuxApp(id.removeSuffix(".desktop"))
            }
            after(LINUX)?.let { command ->
                require(command.isNotEmpty()) { "$LINUX takes a command" }
                return RunLinux(command)
            }
            after(TERMINAL)?.let { command ->
                require(command.isNotEmpty()) { "$TERMINAL takes a command" }
                return RunInTerminal(command)
            }
            after(AGENT)?.let { prompt ->
                require(prompt.isNotEmpty()) { "$AGENT takes a prompt" }
                return AskAgent(prompt)
            }
            // Before actions, a gesture took a panel's bare file name.
            if (value.endsWith(".owl") && value.length > 4 && '/' !in value && ':' !in value) return OpenPanel(value)
            throw IllegalArgumentException(TAKES)
        }

        /** [text] as an action, or null if it isn't one. */
        fun parseOrNull(text: String?): GestureAction? = text?.let { runCatching { parse(it) }.getOrNull() }
    }
}

/** The panel [this] opens; null for an action that opens none. */
val GestureAction.panel: String? get() = (this as? GestureAction.OpenPanel)?.file

/** Whether [this] needs the launcher's accessibility service, which the user turns on. */
val GestureAction.needsAccessibility get() = this == GestureAction.Lock || this == GestureAction.Recents
