package dev.omalauncher.pp.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.provider.Settings
import android.util.Log
import dev.omalauncher.pp.AppLanguage
import dev.omalauncher.pp.R
import dev.omalauncher.pp.widgets.lang.matchScore
import java.util.Locale

/** One of Android's settings pages the bar finds: the [action] that opens it, its name and the other words that find it. */
private class SettingsPage(
    val action: String,
    val label: Int,
    val keys: Int,
)

/** A page as the bar lists it: its name in the launcher's language, and how well it matched. */
class SettingsHit(
    val action: String,
    val title: String,
    val score: Int,
)

/**
 * Android's settings pages by name, as Settings.ACTION_* opens them. A page matches by its name or its other words in
 * any of the launcher's languages, so "wlan" and "bateria" both find theirs whatever the phone is set to; it's listed by
 * its name in the language the launcher shows.
 */
class SettingsIndex(
    private val context: Context,
) {
    private val pages =
        listOf(
            SettingsPage(Settings.ACTION_WIFI_SETTINGS, R.string.bar_search_page_wifi, R.string.bar_search_keys_wifi),
            SettingsPage(Settings.ACTION_BLUETOOTH_SETTINGS, R.string.bar_search_page_bluetooth, R.string.bar_search_keys_bluetooth),
            SettingsPage(Settings.ACTION_WIRELESS_SETTINGS, R.string.bar_search_page_network, R.string.bar_search_keys_network),
            SettingsPage(Settings.ACTION_DATA_USAGE_SETTINGS, R.string.bar_search_page_data_usage, R.string.bar_search_keys_data_usage),
            SettingsPage(Settings.ACTION_AIRPLANE_MODE_SETTINGS, R.string.bar_search_page_airplane, R.string.bar_search_keys_airplane),
            SettingsPage(Settings.ACTION_VPN_SETTINGS, R.string.bar_search_page_vpn, R.string.bar_search_keys_vpn),
            SettingsPage(Settings.ACTION_NFC_SETTINGS, R.string.bar_search_page_nfc, R.string.bar_search_keys_nfc),
            SettingsPage(Settings.ACTION_CAST_SETTINGS, R.string.bar_search_page_cast, R.string.bar_search_keys_cast),
            SettingsPage(Intent.ACTION_POWER_USAGE_SUMMARY, R.string.bar_search_page_battery, R.string.bar_search_keys_battery),
            SettingsPage(Settings.ACTION_BATTERY_SAVER_SETTINGS, R.string.bar_search_page_battery_saver, R.string.bar_search_keys_battery_saver),
            SettingsPage(Settings.ACTION_DISPLAY_SETTINGS, R.string.bar_search_page_display, R.string.bar_search_keys_display),
            // Hidden from the SDK, though Android 10 on has it.
            SettingsPage("android.settings.DARK_THEME_SETTINGS", R.string.bar_search_page_dark_theme, R.string.bar_search_keys_dark_theme),
            SettingsPage(Settings.ACTION_NIGHT_DISPLAY_SETTINGS, R.string.bar_search_page_night_light, R.string.bar_search_keys_night_light),
            SettingsPage(Settings.ACTION_SOUND_SETTINGS, R.string.bar_search_page_sound, R.string.bar_search_keys_sound),
            // Not in the SDK, but every Android since 5 has it; a phone without opens Android's settings instead.
            SettingsPage("android.settings.ZEN_MODE_SETTINGS", R.string.bar_search_page_dnd, R.string.bar_search_keys_dnd),
            SettingsPage("android.settings.NOTIFICATION_SETTINGS", R.string.bar_search_page_notifications, R.string.bar_search_keys_notifications),
            SettingsPage(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, R.string.bar_search_page_apps, R.string.bar_search_keys_apps),
            SettingsPage(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, R.string.bar_search_page_default_apps, R.string.bar_search_keys_default_apps),
            SettingsPage(Settings.ACTION_HOME_SETTINGS, R.string.bar_search_page_home_app, R.string.bar_search_keys_home_app),
            SettingsPage(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, R.string.bar_search_page_storage, R.string.bar_search_keys_storage),
            SettingsPage(Settings.ACTION_LOCATION_SOURCE_SETTINGS, R.string.bar_search_page_location, R.string.bar_search_keys_location),
            SettingsPage(Settings.ACTION_SECURITY_SETTINGS, R.string.bar_search_page_security, R.string.bar_search_keys_security),
            SettingsPage(Settings.ACTION_PRIVACY_SETTINGS, R.string.bar_search_page_privacy, R.string.bar_search_keys_privacy),
            SettingsPage(Settings.ACTION_SYNC_SETTINGS, R.string.bar_search_page_accounts, R.string.bar_search_keys_accounts),
            SettingsPage(Settings.ACTION_ACCESSIBILITY_SETTINGS, R.string.bar_search_page_accessibility, R.string.bar_search_keys_accessibility),
            SettingsPage(Settings.ACTION_LOCALE_SETTINGS, R.string.bar_search_page_language, R.string.bar_search_keys_language),
            SettingsPage(Settings.ACTION_INPUT_METHOD_SETTINGS, R.string.bar_search_page_keyboard, R.string.bar_search_keys_keyboard),
            SettingsPage(Settings.ACTION_DATE_SETTINGS, R.string.bar_search_page_date, R.string.bar_search_keys_date),
            SettingsPage(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, R.string.bar_search_page_developer, R.string.bar_search_keys_developer),
            SettingsPage(Settings.ACTION_DEVICE_INFO_SETTINGS, R.string.bar_search_page_about, R.string.bar_search_keys_about),
            SettingsPage(Settings.ACTION_SETTINGS, R.string.bar_search_page_android, R.string.bar_search_keys_android),
        )

    /** Each page's names and words, in every language the launcher has: read once, they don't change while it runs. */
    private val words: List<List<String>> by lazy {
        val all = AppLanguage.supported.map { resourcesIn(it) }
        // The words whole too, so "usb debugging" finds developer options as "usb" alone does.
        pages.map { p -> all.flatMap { r -> r.getString(p.keys).let { keys -> listOf(r.getString(p.label), keys) + keys.split(' ').filter { it.isNotBlank() } } }.distinct() }
    }

    private fun resourcesIn(tag: String): Resources {
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) }
        return context.createConfigurationContext(config).resources
    }

    /**
     * The pages [query] names, each scored as an app's name would be; its name or any one of its words counts. Only from
     * a word's start: letters scattered through a word ("di" in "audio") would list half the pages.
     */
    fun search(query: String): List<SettingsHit> {
        if (query.trim().length < 2) return emptyList()
        val shown = AppLanguage.resources(context, context.resources)
        return pages.mapIndexedNotNull { i, page ->
            val names = words[i]
            val score = names.maxOf { matchScore(it, query) }
            if (score >= MIN_SCORE) SettingsHit(page.action, shown.getString(page.label), score) else null
        }
    }

    /** Opens the page [action] names; Android's settings themselves where the phone doesn't have that page. */
    fun open(action: String) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(Intent(action).addFlags(flags))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no settings page for $action", e)
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flags)) }
        }
    }

    private companion object {
        const val TAG = "search"

        /** matchScore's initials tier: a word's start, or the words' first letters. */
        const val MIN_SCORE = 300
    }
}
