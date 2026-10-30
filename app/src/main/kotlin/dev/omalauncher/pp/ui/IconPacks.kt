package dev.omalauncher.pp.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * An icon pack app: another launcher's set of app icons, as packs are shared between them all. Each maps components
 * to drawables of its own in an `appfilter.xml` (in its assets or its `res/xml`), optionally with a background
 * (`iconback`), a mask (`iconmask`) and an overlay (`iconupon`), and a `scale` for the icon itself.
 * From Omalauncher+. Each icon is drawn once, as the catalog draws every app's icon once, so nothing is cached here.
 */
class IconPack(
    context: Context,
    val packageName: String,
) {
    private val resources: Resources = context.packageManager.getResourcesForApplication(packageName)

    /** Component, as [normalize] makes it, -> drawable name. */
    private val entries = HashMap<String, String>()
    private var back: String? = null
    private var mask: String? = null
    private var upon: String? = null
    private var scale: Float = 1f

    init {
        parse()
    }

    /**
     * Reads the pack's `appfilter.xml`: `assets/appfilter.xml` for most packs, `res/xml/appfilter.xml` for the rest.
     * Either source can be all a pack has; one with no entries means an unusable pack.
     */
    private fun parse() {
        var assets: java.io.InputStream? = null
        val fromAssets =
            runCatching {
                resources.assets.open("appfilter.xml").also { assets = it }.let { stream -> Xml.newPullParser().apply { setInput(stream, null) } }
            }.getOrNull()
        if (fromAssets != null) {
            runCatching { read(fromAssets) }.onFailure { Log.w(TAG, "cannot read $packageName's appfilter", it) }
            runCatching<Unit> { assets?.close() }
            if (entries.isNotEmpty()) return
        }
        val xmlId = runCatching { resources.getIdentifier("appfilter", "xml", packageName) }.getOrDefault(0)
        if (xmlId != 0) {
            val fromXml = runCatching { resources.getXml(xmlId) }.getOrNull()
            if (fromXml != null) {
                runCatching { read(fromXml) }.onFailure { Log.w(TAG, "cannot read $packageName's appfilter", it) }
                runCatching<Unit> { fromXml.close() }
            }
        }
    }

    /** Whether the pack's `appfilter.xml` had any entries: a declared pack without one draws nothing. */
    val hasEntries get() = entries.isNotEmpty()

    private fun read(parser: XmlPullParser) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null) entries[normalize(component)] = drawable
                    }
                    "scale" -> parser.getAttributeValue(null, "factor")?.toFloatOrNull()?.let { scale = it.coerceIn(0.1f, 1f) }
                    "iconback" -> parser.getAttributeValue(null, "img")?.let { back = it }
                    "iconmask" -> parser.getAttributeValue(null, "img")?.let { mask = it }
                    "iconupon" -> parser.getAttributeValue(null, "img")?.let { upon = it }
                }
            }
            event = parser.next()
        }
    }

    /** The pack's icon for [component], or null when the pack has none, so the app's own icon stays. */
    fun iconFor(component: ComponentName): Drawable? {
        // The activity an app opens from the drawer, or the one the icon was drawn for.
        val name = entries[component.flattenToString()] ?: entries[component.flattenToShortString()] ?: return null
        val icon = drawable(name) ?: return null
        val back = drawable(back)
        val mask = drawable(mask)
        val upon = drawable(upon)
        if (back == null && mask == null && upon == null && scale == 1f) return icon
        return compose(icon, back, mask, upon, scale)
    }

    private fun drawable(name: String?): Drawable? {
        if (name.isNullOrBlank()) return null
        // Packs name drawables plainly ("gmail"), or with their package or resource ("com.pack.gmail/icon").
        val plain = name.substringAfterLast('/').removePrefix("@").substringAfterLast('/')
        val id = listOf("drawable", "mipmap").map { resources.getIdentifier(plain, it, packageName) }.firstOrNull { it != 0 } ?: return null
        return runCatching { resources.getDrawable(id, null) }.getOrNull()
    }

    /** [icon] at [scale] over [back], cut out by [mask] and covered by [upon], the layers icon packs compose. */
    private fun compose(
        icon: Drawable,
        back: Drawable?,
        mask: Drawable?,
        upon: Drawable?,
        scale: Float,
    ): Drawable {
        val size = (108 * resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val behind = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(behind).run {
            back?.let { it.setBounds(0, 0, size, size); it.draw(this) }
            val inset = (size * (1f - scale) / 2f).toInt()
            icon.setBounds(inset, inset, size - inset, size - inset)
            icon.draw(this)
        }
        val composed =
            if (mask == null) {
                behind
            } else {
                // The mask's alpha keeps the icon and the background where it is and drops the rest.
                val cut = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                Canvas(layer).run { mask.setBounds(0, 0, size, size); mask.draw(this) }
                Canvas(cut).run {
                    drawBitmap(behind, 0f, 0f, null)
                    drawBitmap(layer, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                }
                cut
            }
        Canvas(composed).run { upon?.let { it.setBounds(0, 0, size, size); it.draw(this) } }
        return BitmapDrawable(resources, composed)
    }

    companion object {
        private const val TAG = "omalauncher-pack"

        /** `ComponentInfo{pkg/cls}` or the short form to one key, so spellings of the same activity map alike. */
        fun normalize(component: String) = component.removePrefix("ComponentInfo{").removeSuffix("}")
    }
}

/** An installed icon pack, as settings list it. */
data class IconPackInfo(
    val packageName: String,
    val label: String,
)

/**
 * The icon packs installed on the phone, and the one style.toml's icons.pack picks, read once it's first asked for an
 * icon. Packs declare themselves with another launcher's theme (see the manifest's `<queries>`); Android hides them
 * from the app otherwise.
 */
class IconPackStore(
    private val context: Context,
) {
    private val packageManager = context.packageManager

    /** Installed packs by name, as of the last [list]. */
    var installed by mutableStateOf<List<IconPackInfo>>(emptyList())
        private set

    /**
     * When the picked pack was last installed or updated, 0 while it isn't: part of [IconLook], so installing,
     * updating or removing it draws the icons again.
     */
    var stamp by mutableLongStateOf(0L)
        private set

    /** The pack read for [readFor] (its name and stamp); null when there's none or it can't be read. */
    private var pack: IconPack? = null
    private var readFor: Pair<String, Long>? = null

    /** [component]'s icon from the pack [look] picks, or null for the app's own. Called off the main thread, several at once. */
    fun iconFor(
        look: IconLook,
        component: ComponentName,
    ): Drawable? {
        val name = look.pack ?: return null
        val pack =
            synchronized(this) {
                val key = name to look.packStamp
                if (readFor != key) {
                    readFor = key
                    pack = runCatching { IconPack(context, name) }.onFailure { Log.w(TAG, "cannot read icon pack $name", it) }.getOrNull()?.takeIf { it.hasEntries }
                }
                pack
            }
        return runCatching { pack?.iconFor(component) }.getOrNull()
    }

    /** Checks [picked] again, as it's picked and as home shows, so a pack installed, updated or removed meanwhile shows. Quick. */
    fun check(picked: String?) {
        val now = stampOf(picked)
        if (now != stamp) stamp = now
    }

    /** Lists the installed packs into [installed], for settings. */
    suspend fun list() {
        installed = withContext(Dispatchers.IO) { findPacks() }
    }

    /** Every app on the phone declared as an icon pack for one launcher or another, by name. */
    private fun findPacks(): List<IconPackInfo> {
        val found = LinkedHashSet<String>()
        // Most launchers share packs through `MAIN` + a theme category; a few through a theme action.
        for (theme in PACK_THEMES) {
            for (intent in listOf(Intent(Intent.ACTION_MAIN).addCategory(theme), Intent(theme))) {
                val listed = runCatching { packageManager.queryIntentActivities(intent, 0) }.getOrNull().orEmpty()
                for (info in listed) found += info.activityInfo.packageName
            }
        }
        return found
            .mapNotNull { pkg -> labelOf(pkg)?.let { IconPackInfo(pkg, it) } }
            .sortedBy { it.label.lowercase() }
    }

    private fun labelOf(packageName: String): String? =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** When [packageName] was last installed or updated; 0 with none, or when it isn't installed. */
    private fun stampOf(packageName: String?) =
        packageName?.let { runCatching { packageManager.getPackageInfo(it, 0).lastUpdateTime }.getOrDefault(0L) } ?: 0L

    private companion object {
        const val TAG = "omalauncher-pack"

        /** The themes icon packs declare themselves with, shared between launchers this way; the manifest's `<queries>` lists them too. */
        val PACK_THEMES =
            listOf(
                "com.novalauncher.THEME",
                "org.adw.launcher.THEMES",
                "org.adw.launcher.icons.THEMES",
                "com.gau.go.launcherex.theme",
                "com.anddoes.launcher.THEME",
                "com.teslacoilsw.launcher.THEME",
                "com.fede.launcher.THEME_ICONPACK",
                "com.motorola.launcher3.THEME",
                "com.sonymobile.home.THEME",
            )
    }
}
