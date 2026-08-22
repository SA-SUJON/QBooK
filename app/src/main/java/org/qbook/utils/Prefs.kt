package org.qbook.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Single source of truth for every user setting.
 * Keys must match the active settings resources under res/xml/settings_control_center.xml.
 */
class Prefs(context: Context) {

    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    val diagLog: DiagnosticLog = DiagnosticLog(context.applicationContext)

    init {
        // OLED is valid only in explicit Dark Mode. Clear stale state when
        // System Synchronized or Light Mode is active.
        if (sp.getString(KEY_DARK_MODE, DARK_SYSTEM) != DARK_DARK &&
            sp.getBoolean(KEY_AMOLED, false)
        ) {
            sp.edit().putBoolean(KEY_AMOLED, false).apply()
        }
    }

    companion object {
        // Blocking
        const val KEY_AD_BLOCK = "ad_block_enabled"
        const val KEY_COSMETIC = "cosmetic_filter_enabled"
        const val KEY_BLOCK_APP_PROMO = "block_app_promo"
        const val KEY_BLOCK_POPUPS = "block_popups"
        const val KEY_BLOCK_COUNT = "block_count"

        // Home page sections
        const val KEY_HIDE_STORIES = "hide_stories"
        const val KEY_HIDE_REELS = "hide_reels"
        const val KEY_HIDE_ROOMS = "hide_rooms"
        const val KEY_HIDE_MARKETPLACE = "hide_marketplace"
        const val KEY_HIDE_GROUPS = "hide_groups"
        const val KEY_HIDE_WATCH = "hide_watch"
        const val KEY_HIDE_EVENTS = "hide_events"
        const val KEY_HIDE_GAMING = "hide_gaming"
        const val KEY_HIDE_MEMORIES = "hide_memories"
        const val KEY_HIDE_BIRTHDAYS = "hide_birthdays"
        const val KEY_HIDE_PYMK = "hide_pymk"
        const val KEY_HIDE_PAGES = "hide_pages"

        // Appearance
        const val KEY_DARK_MODE = "dark_mode"          // system | light | dark
        const val KEY_AMOLED = "amoled_black"
        const val KEY_SHOW_PROGRESS = "show_progress_bar"
        const val KEY_MATERIAL_YOU = "material_you"
        const val KEY_APP_ICON = "app_icon"
        const val KEY_BACKGROUND_AUDIO = "background_audio"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_CUSTOM_FONT_NAME = "custom_font_name"
        const val KEY_CUSTOM_FONT_MIME = "custom_font_mime"

        // Offline
        const val KEY_OFFLINE_MODE = "offline_mode"
        const val KEY_OFFLINE_VIDEO = "offline_video"
        const val KEY_OFFLINE_REELS = "offline_reels"
        const val KEY_OFFLINE_FEED = "offline_feed"
        const val KEY_OFFLINE_STORIES = "offline_stories"
        const val KEY_OFFLINE_LAST_SYNC = "offline_last_sync"
        const val KEY_OFFLINE_REEL_COUNT = "offline_reel_count"
        const val KEY_OFFLINE_POST_COUNT = "offline_post_count"
        const val KEY_OFFLINE_WIFI_ONLY = "offline_network"
        const val KEY_OFFLINE_RESUME_REEL = "offline_resume_reel"
        const val KEY_OFFLINE_RESUME_FEED = "offline_resume_feed"
        const val KEY_OFFLINE_RESUME_STORIES = "offline_resume_stories"
        const val KEY_INAPP_MESSAGING = "inapp_messaging"

        // Updates
        const val KEY_PUSH_NOTIFICATIONS = "push_notifications"

        // Support the developer
        const val KEY_SUPPORT_HIDDEN = "support_hidden"
        const val KEY_SUPPORT_LAST_SHOWN = "support_last_shown"
        const val KEY_LAUNCH_COUNT = "launch_count"
        const val KEY_SUPPORT_DONATED_AT = "support_donated_at"
        const val KEY_FIRST_LAUNCH_AT = "first_launch_at"

        // Updates
        const val KEY_AUTO_UPDATE = "auto_update_check"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"

        // Browsing
        const val KEY_DESKTOP_MODE = "desktop_mode"
        const val KEY_PULL_REFRESH = "pull_to_refresh"
        const val KEY_ZOOM = "allow_zoom"
        const val KEY_AUTOPLAY_VIDEO = "autoplay_video"
        const val KEY_MEDIA_DOWNLOADER = "media_downloader"
        const val KEY_EXTERNAL_BROWSER = "open_links_external"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_COPY_MEDIA_CLIPBOARD = "copy_media_clipboard"
        const val KEY_STICKY_NAVBAR = "sticky_navbar"
        const val KEY_IMMERSIVE_MODE = "immersive_mode"
        const val KEY_INPAGE_SETTINGS = "inpage_settings"
        const val KEY_SELECTABLE_CAPTIONS = "selectable_captions"
        const val KEY_INSPECT_ADS = "inspect_ads"
        const val KEY_LOG_VIDEOS = "log_video_urls"
        const val KEY_DIAGNOSTIC_LOG = "diagnostic_log"
        const val KEY_DIAGNOSTIC_HOME_FEED = "diag_home_feed"
        const val KEY_DIAGNOSTIC_REELS = "diag_reels"
        const val KEY_DIAGNOSTIC_STORY = "diag_story"
        const val KEY_DIAGNOSTIC_ADS = "diag_ads"
        const val KEY_DIAGNOSTIC_NETWORK = "diag_network"
        const val KEY_DIAGNOSTIC_OFFLINE_SAVE = "diag_offline_save"
        const val KEY_DIAGNOSTIC_LIFECYCLE = "diag_lifecycle"
        const val KEY_DIAGNOSTIC_ALL = "diag_all"
        const val KEY_DEVELOPER_UNLOCKED = "developer_unlocked"
        const val KEY_DEVELOPER_ENABLED = "developer_enabled"
        const val KEY_DEV_TAP_COUNT = "dev_tap_count"
        const val KEY_DEV_TAP_FIRST_AT = "dev_tap_first_at"
        const val KEY_HOMEPAGE = "homepage_url"

        // QBooK Labs (optional experimental features)
        const val KEY_LABS_DOWNLOAD_CENTER = "labs_download_center"
        const val KEY_LABS_REEL_OPTIONS = "labs_reel_options"
        const val KEY_LABS_SAVE_PATHS = "labs_save_paths"
        const val KEY_LABS_AUDIO_EXTRACTION = "labs_audio_extraction"
        const val KEY_LABS_GALLERY = "labs_gallery"
        const val KEY_LABS_BATCH_SAVE = "labs_batch_save"
        const val KEY_LABS_DYNAMIC_INDICATOR = "labs_dynamic_indicator"
        const val KEY_LABS_ANIMATED_THEME = "labs_animated_theme"
        const val KEY_LABS_TOOLBOX = "labs_toolbox"
        const val KEY_LABS_EXTENDED_MATERIAL = "labs_extended_material"
        const val KEY_LABS_MATERIALBOOK_DESKTOP_CLEANUP = "labs_materialbook_desktop_cleanup"
        const val KEY_LABS_MATERIALBOOK_TRANSPARENT_PROGRESS = "labs_materialbook_transparent_progress"
        const val KEY_LABS_MATERIALBOOK_GREY_TAP = "labs_materialbook_grey_tap"
        const val KEY_LABS_APPEAR_OFFLINE = "labs_appear_offline"
        const val KEY_LABS_APP_LOCK = "labs_app_lock"
        const val KEY_LABS_STRIP_TRACKING = "labs_strip_tracking"
        const val KEY_LABS_FLAG_SECURE = "labs_flag_secure"
        const val KEY_LABS_LIQUID_GLASS = "labs_liquid_glass"
        const val KEY_LABS_SHOW_DISCLAIMER = "labs_show_disclaimer"
        const val KEY_ACCOUNTS_SHOW_ON_START = "accounts_show_on_start"

        // Data
        const val KEY_SAVE_SESSION = "save_session"
        const val KEY_LAST_URL = "last_url"

        const val DARK_SYSTEM = "system"
        const val DARK_LIGHT = "light"
        const val DARK_DARK = "dark"

        /** Preference key -> JS flag name used by AdBlocker.getCosmeticScript. */
        val SECTION_KEYS = linkedMapOf(
            KEY_HIDE_STORIES to "stories",
            KEY_HIDE_REELS to "reels",
            KEY_HIDE_ROOMS to "rooms",
            KEY_HIDE_MARKETPLACE to "marketplace",
            KEY_HIDE_GROUPS to "groups",
            KEY_HIDE_WATCH to "watch",
            KEY_HIDE_EVENTS to "events",
            KEY_HIDE_GAMING to "gaming",
            KEY_HIDE_MEMORIES to "memories",
            KEY_HIDE_BIRTHDAYS to "birthdays",
            KEY_HIDE_PYMK to "pymk",
            KEY_HIDE_PAGES to "pages"
        )
    }

    var adBlock: Boolean
        get() = sp.getBoolean(KEY_AD_BLOCK, true)
        set(v) = sp.edit().putBoolean(KEY_AD_BLOCK, v).apply()

    val cosmeticFilter: Boolean get() = sp.getBoolean(KEY_COSMETIC, true)
    val blockAppPromo: Boolean get() = sp.getBoolean(KEY_BLOCK_APP_PROMO, true)
    val blockPopups: Boolean get() = sp.getBoolean(KEY_BLOCK_POPUPS, true)

    val darkMode: String get() = sp.getString(KEY_DARK_MODE, DARK_SYSTEM) ?: DARK_SYSTEM
    var amoled: Boolean
        get() = darkMode == DARK_DARK && sp.getBoolean(KEY_AMOLED, false)
        set(value) {
            sp.edit().putBoolean(KEY_AMOLED, value && darkMode == DARK_DARK).apply()
        }
    val showProgress: Boolean get() = sp.getBoolean(KEY_SHOW_PROGRESS, false)
    val materialYou: Boolean get() = sp.getBoolean(KEY_MATERIAL_YOU, true)
    /** 0-15 index for app icon selection. 0 is the default icon. */
    val appIcon: Int get() = (sp.getString(KEY_APP_ICON, "0")?.toIntOrNull() ?: 0).coerceIn(0, 15)
    val backgroundAudio: Boolean get() = sp.getBoolean(KEY_BACKGROUND_AUDIO, false)
    /** Active bundled font asset name, `system`, or `custom`. */
    var fontFamily: String
        get() = sp.getString(KEY_FONT_FAMILY, FontManager.SYSTEM_VALUE) ?: FontManager.SYSTEM_VALUE
        set(value) = sp.edit().putString(KEY_FONT_FAMILY, value).apply()
    /** Native WebView text zoom percentage. 100 is the Facebook default. */
    var fontScale: Int
        get() = sp.getString(KEY_FONT_SCALE, "100")?.toIntOrNull()?.coerceIn(75, 175) ?: 100
        set(value) = sp.edit().putString(KEY_FONT_SCALE, value.coerceIn(75, 175).toString()).apply()
    var customFontName: String
        get() = sp.getString(KEY_CUSTOM_FONT_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_CUSTOM_FONT_NAME, value).apply()
    var customFontMime: String
        get() = sp.getString(KEY_CUSTOM_FONT_MIME, "font/ttf") ?: "font/ttf"
        set(value) = sp.edit().putString(KEY_CUSTOM_FONT_MIME, value).apply()
    /**
     * Offline saving as a whole. There is no switch for this any more - the
     * three per-section switches are the control - so it follows them.
     */
    val offlineMode: Boolean
        get() = sp.getBoolean(KEY_OFFLINE_MODE, true) &&
            (offlineReels || offlineFeed || offlineStories)

    /**
     * Whether stored content may be *shown*.
     *
     * Deliberately not [offlineMode]. Those switches decide what gets saved
     * from now on; they say nothing about what is already on disk. Tying
     * display to them meant a user who turned saving off could no longer read
     * content they had already downloaded — it was still there, and the app
     * pretended it was not. Turning the switches back on made it reappear,
     * which is the giveaway that nothing had actually been deleted.
     *
     * Showing what exists is always allowed. Deleting is a separate, explicit
     * action (Offline → Clear saved content).
     */
    val offlineRead: Boolean get() = true
    /**
     * Restrict offline downloading to unmetered networks.
     *
     * Defaults to true. A full pass fetches feed pages, reels and their video,
     * which is hundreds of megabytes; doing that silently on a mobile plan is
     * the kind of thing a user only notices once the data is gone. Opting in
     * to mobile data is a decision they should make deliberately.
     */
    val offlineWifiOnly: Boolean
        get() = when (sp.getString(KEY_OFFLINE_WIFI_ONLY, "wifi")) {
            "any" -> false
            else -> true
        }

    /** Video is what "keep reels" means, so it is not a separate choice. */
    val offlineVideo: Boolean get() = sp.getBoolean(KEY_OFFLINE_VIDEO, true)

    /** Keep reels playable with no connection. */
    val offlineReels: Boolean get() = sp.getBoolean(KEY_OFFLINE_REELS, true)

    /**
     * Same for the home feed. On by default: with it off nothing was ever
     * stored for the home screen, so opening the app offline showed Facebook's
     * loading skeleton and nothing else.
     */
    val offlineFeed: Boolean get() = sp.getBoolean(KEY_OFFLINE_FEED, true)

    /**
     * When a sync last completed with content actually stored.
     *
     * Written only after a pass succeeds, so it reports what the offline
     * library really is rather than when something was last attempted.
     */
    var offlineLastSync: Long
        get() = sp.getLong(KEY_OFFLINE_LAST_SYNC, 0L)
        set(v) = sp.edit().putLong(KEY_OFFLINE_LAST_SYNC, v).apply()

    /** Keep stories readable with no connection. */
    val offlineStories: Boolean get() = sp.getBoolean(KEY_OFFLINE_STORIES, true)

    /**
     * How many reels to hold. Stored as a string because ListPreference only
     * writes strings; a bad value falls back to the default rather than
     * crashing the settings screen.
     */
    /** 
     * V4: Significantly raised default (150).
     * Goal: Proactively prepare fresh offline content in background.
     */
    val offlineReelTarget: Int
        get() {
            val raw = sp.getString(KEY_OFFLINE_REEL_COUNT, null) ?: return 30
            return raw.toIntOrNull()?.coerceIn(30, 250) ?: 30
        }

    /**
     * How many feed posts to hold, user-chosen exactly like the reel
     * count. This number is the hard ceiling for every capture path: the
     * pipeline's exact totals and the foreground merge both refuse to add
     * past it, and the vault trims down to it when a sync starts - the
     * days of "10 bolchilam, 134 holo keno" die here.
     *
     * Must stay in step with post_count_values in strings.xml and the
     * defaultValue in settings_offline.xml.
     */
    val offlinePostTarget: Int
        get() {
            val raw = sp.getString(KEY_OFFLINE_POST_COUNT, null) ?: return 50
            return raw.toIntOrNull()?.coerceIn(10, 300) ?: 50
        }
    val inAppMessaging: Boolean get() = sp.getBoolean(KEY_INAPP_MESSAGING, false)
    /**
     * Check Facebook for new activity in the background.
     *
     * Off by default: it costs a page load every fifteen minutes, and a user
     * who has not asked for notifications should not pay for them.
     */
    val pushNotifications: Boolean get() = sp.getBoolean(KEY_PUSH_NOTIFICATIONS, false)

    /**
     * Diagnostic only. Shows the real layout numbers on a long-press, so a
     * layout fault can be measured on the device instead of guessed at from
     * the source. Off by default and draws nothing until it is switched on.
     */

    /** Set by the "Don't show again" box. Silences the automatic prompt only. */
    var supportHidden: Boolean
        get() = sp.getBoolean(KEY_SUPPORT_HIDDEN, false)
        set(v) = sp.edit().putBoolean(KEY_SUPPORT_HIDDEN, v).apply()

    var supportLastShown: Long
        get() = sp.getLong(KEY_SUPPORT_LAST_SHOWN, 0L)
        set(v) = sp.edit().putLong(KEY_SUPPORT_LAST_SHOWN, v).apply()

    /**
     * When the user pressed Donate, or 0.
     *
     * Not a receipt - the app cannot verify a payment. It records that they
     * decided, which is enough to stop asking them.
     */
    var supportDonatedAt: Long
        get() = sp.getLong(KEY_SUPPORT_DONATED_AT, 0L)
        set(v) = sp.edit().putLong(KEY_SUPPORT_DONATED_AT, v).apply()

    /** How many times the app has been opened, for "has this been useful yet". */
    var launchCount: Int
        get() = sp.getInt(KEY_LAUNCH_COUNT, 0)
        set(v) = sp.edit().putInt(KEY_LAUNCH_COUNT, v).apply()

    /** When the app was opened for the very first time. 0 until set. */
    var firstLaunchAt: Long
        get() = sp.getLong(KEY_FIRST_LAUNCH_AT, 0L)
        set(v) = sp.edit().putLong(KEY_FIRST_LAUNCH_AT, v).apply()

    val autoUpdateCheck: Boolean get() = sp.getBoolean(KEY_AUTO_UPDATE, true)

    /** Last reel the user watched offline — next session resumes here. */
    var offlineResumeReel: String?
        get() = sp.getString(KEY_OFFLINE_RESUME_REEL, null)
        set(v) = sp.edit().putString(KEY_OFFLINE_RESUME_REEL, v).apply()
    /** Last feed scroll offset offline — next session resumes here. */
    var offlineResumeFeed: String?
        get() = sp.getString(KEY_OFFLINE_RESUME_FEED, null)
        set(v) = sp.edit().putString(KEY_OFFLINE_RESUME_FEED, v).apply()
    /** Last story id viewed offline — next session resumes here. */
    var offlineResumeStories: String?
        get() = sp.getString(KEY_OFFLINE_RESUME_STORIES, null)
        set(v) = sp.edit().putString(KEY_OFFLINE_RESUME_STORIES, v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()


    val desktopMode: Boolean get() = sp.getBoolean(KEY_DESKTOP_MODE, false)
    /**
     * Defaults on. Offline it is the only way to pick up content that has
     * finished downloading since the page was built, and a gesture that does
     * nothing reads as the app being broken rather than as a setting being
     * off.
     */
    val pullToRefresh: Boolean get() = sp.getBoolean(KEY_PULL_REFRESH, true)
    val allowZoom: Boolean get() = sp.getBoolean(KEY_ZOOM, false)
    val autoplayVideo: Boolean get() = sp.getBoolean(KEY_AUTOPLAY_VIDEO, true)
    val mediaDownloader: Boolean get() = sp.getBoolean(KEY_MEDIA_DOWNLOADER, true)

    // Labs defaults preserve the currently shipped downloader/glass behavior while
    // keeping newer experimental surfaces opt-in.
    val labsDownloadCenter: Boolean get() = sp.getBoolean(KEY_LABS_DOWNLOAD_CENTER, true)
    val labsReelOptions: Boolean get() = sp.getBoolean(KEY_LABS_REEL_OPTIONS, true)
    val labsSavePaths: Boolean get() = sp.getBoolean(KEY_LABS_SAVE_PATHS, false)
    val labsAudioExtraction: Boolean get() = sp.getBoolean(KEY_LABS_AUDIO_EXTRACTION, false)
    val labsGallery: Boolean get() = sp.getBoolean(KEY_LABS_GALLERY, true)
    val labsBatchSave: Boolean get() = sp.getBoolean(KEY_LABS_BATCH_SAVE, false)
    val labsDynamicIndicator: Boolean get() = sp.getBoolean(KEY_LABS_DYNAMIC_INDICATOR, true)
    val labsAnimatedTheme: Boolean get() = sp.getBoolean(KEY_LABS_ANIMATED_THEME, false)
    val labsToolbox: Boolean get() = sp.getBoolean(KEY_LABS_TOOLBOX, false)
    val labsExtendedMaterial: Boolean get() = sp.getBoolean(KEY_LABS_EXTENDED_MATERIAL, false)
    val labsMaterialbookDesktopCleanup: Boolean get() = sp.getBoolean(KEY_LABS_MATERIALBOOK_DESKTOP_CLEANUP, false)
    val labsMaterialbookTransparentProgress: Boolean get() = sp.getBoolean(KEY_LABS_MATERIALBOOK_TRANSPARENT_PROGRESS, false)
    val labsMaterialbookGreyTap: Boolean get() = sp.getBoolean(KEY_LABS_MATERIALBOOK_GREY_TAP, false)
    val labsAppearOffline: Boolean get() = sp.getBoolean(KEY_LABS_APPEAR_OFFLINE, false)
    val labsAppLock: Boolean get() = sp.getBoolean(KEY_LABS_APP_LOCK, false)
    val labsStripTracking: Boolean get() = sp.getBoolean(KEY_LABS_STRIP_TRACKING, false)
    val labsFlagSecure: Boolean get() = sp.getBoolean(KEY_LABS_FLAG_SECURE, false)
    val labsLiquidGlass: Boolean get() = sp.getBoolean(KEY_LABS_LIQUID_GLASS, true)
    val labsShowDisclaimer: Boolean get() = sp.getBoolean(KEY_LABS_SHOW_DISCLAIMER, true)
    val accountsShowOnStart: Boolean get() = sp.getBoolean(KEY_ACCOUNTS_SHOW_ON_START, true)

    val openLinksExternal: Boolean get() = sp.getBoolean(KEY_EXTERNAL_BROWSER, false)
    val keepScreenOn: Boolean get() = sp.getBoolean(KEY_KEEP_SCREEN_ON, false)
    val haptics: Boolean get() = sp.getBoolean(KEY_HAPTICS, false)
    val copyMediaToClipboard: Boolean get() = sp.getBoolean(KEY_COPY_MEDIA_CLIPBOARD, false)
    val stickyNavbar: Boolean get() = sp.getBoolean(KEY_STICKY_NAVBAR, false)
    val immersiveMode: Boolean get() = sp.getBoolean(KEY_IMMERSIVE_MODE, false)
    val inPageSettings: Boolean get() = sp.getBoolean(KEY_INPAGE_SETTINGS, false)
    val selectableCaptions: Boolean get() = sp.getBoolean(KEY_SELECTABLE_CAPTIONS, false)

    /** Debug: long-press an ad to capture its markup. Off by default. */
    val inspectAds: Boolean get() = sp.getBoolean(KEY_INSPECT_ADS, false)
    val logVideoUrls: Boolean get() = sp.getBoolean(KEY_LOG_VIDEOS, false)
    /** Diagnostic log: when on, every write path appends to a file in
     *  cacheDir/diagnostic/qbook.log. Off by default; turning it on
     *  has no effect when the user has no Developer-options entry point.
     *
     *  Two pieces of state: the persisted boolean (what the user
     *  asked for, survives process restart) and the volatile
     *  diagLog.enabled (what every write call site reads, must
     *  be set on every process start). The getter returns the
     *  persisted value, not the volatile one - otherwise cold
     *  start would always see `false` because the in-memory flag
     *  has just been constructed. The setter is the only path
     *  that touches the volatile flag. */
    var diagnosticLog: Boolean
        get() = sp.getBoolean(KEY_DIAGNOSTIC_LOG, false)
        set(value) {
            diagLog.enabled = value
            sp.edit().putBoolean(KEY_DIAGNOSTIC_LOG, value).apply()
        }

    /** True once the user has tapped About seven times. Persists across
     *  process restarts and never resets - the seven-tap gesture is the
     *  one-way unlock. What the user toggles after that is
     *  [developerEnabled], the page-top switch on the Developer options
     *  screen. */
    var developerUnlocked: Boolean
        get() = sp.getBoolean(KEY_DEVELOPER_UNLOCKED, false)
        set(v) = sp.edit().putBoolean(KEY_DEVELOPER_UNLOCKED, v).apply()

    /** The page-top switch the user can turn on or off after unlocking.
     *  When off, the Developer options entry is hidden in the About
     *  screen; when on, it is visible. Default is off (the entry is
     *  hidden by default in the layout too, so off is the
     *  never-seen state). Seven-tap sets this to true as well as
     *  setting [developerUnlocked], so the unlock gesture immediately
     *  reveals the entry. */
    var developerEnabled: Boolean
        get() = sp.getBoolean(KEY_DEVELOPER_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_DEVELOPER_ENABLED, v).apply()

    /** Per-channel diagnostic-log enabled flags. Each channel
     *  is a topic the developer can choose to log - home feed,
     *  reels, story, ads, network, offline save, app lifecycle.
     *  The [diagChannelEnabled] helper maps a [Diag.Channel]
     *  to its key, so the [DiagnosticStore] write path is a
     *  single function call. The flags default to false so a
     *  fresh install produces zero disk write even if a
     *  capture point is reached. */
    fun diagChannelEnabled(channel: Diag.Channel): Boolean {
        val key = when (channel) {
            Diag.Channel.HOME_FEED -> KEY_DIAGNOSTIC_HOME_FEED
            Diag.Channel.REELS -> KEY_DIAGNOSTIC_REELS
            Diag.Channel.STORY -> KEY_DIAGNOSTIC_STORY
            Diag.Channel.ADS -> KEY_DIAGNOSTIC_ADS
            Diag.Channel.NETWORK -> KEY_DIAGNOSTIC_NETWORK
            Diag.Channel.OFFLINE_SAVE -> KEY_DIAGNOSTIC_OFFLINE_SAVE
            Diag.Channel.APP_LIFECYCLE -> KEY_DIAGNOSTIC_LIFECYCLE
        }
        return sp.getBoolean(KEY_DIAGNOSTIC_ALL, false) || sp.getBoolean(key, false)
    }
    fun setDiagAllEnabled(value: Boolean) {
        sp.edit().putBoolean(KEY_DIAGNOSTIC_ALL, value).apply()
    }
    fun setDiagChannelEnabled(channel: Diag.Channel, value: Boolean) {
        val key = when (channel) {
            Diag.Channel.HOME_FEED -> KEY_DIAGNOSTIC_HOME_FEED
            Diag.Channel.REELS -> KEY_DIAGNOSTIC_REELS
            Diag.Channel.STORY -> KEY_DIAGNOSTIC_STORY
            Diag.Channel.ADS -> KEY_DIAGNOSTIC_ADS
            Diag.Channel.NETWORK -> KEY_DIAGNOSTIC_NETWORK
            Diag.Channel.OFFLINE_SAVE -> KEY_DIAGNOSTIC_OFFLINE_SAVE
            Diag.Channel.APP_LIFECYCLE -> KEY_DIAGNOSTIC_LIFECYCLE
        }
        sp.edit().putBoolean(key, value).apply()
    }
    /** True when at least one diagnostic channel is on. The
     *  developer-options screen uses this to show a one-line
     *  "no channels on, nothing will be captured" hint
     *  instead of an empty list. */
    val anyDiagChannelEnabled: Boolean
        get() = Diag.Channel.values().any { diagChannelEnabled(it) }

    /** Tap counter and first-tap timestamp for the seven-tap gesture. */
    var devTapCount: Int
        get() = sp.getInt(KEY_DEV_TAP_COUNT, 0)
        set(v) = sp.edit().putInt(KEY_DEV_TAP_COUNT, v).apply()
    var devTapFirstAt: Long
        get() = sp.getLong(KEY_DEV_TAP_FIRST_AT, 0L)
        set(v) = sp.edit().putLong(KEY_DEV_TAP_FIRST_AT, v).apply()
    val saveSession: Boolean get() = sp.getBoolean(KEY_SAVE_SESSION, false)

    val homepage: String
        get() = sp.getString(KEY_HOMEPAGE, "https://www.facebook.com")
            ?.takeIf { it.isNotBlank() } ?: "https://www.facebook.com"

    var lastUrl: String?
        get() = sp.getString(KEY_LAST_URL, null)
        set(v) = sp.edit().putString(KEY_LAST_URL, v).apply()

    var blockCount: Int
        get() = sp.getInt(KEY_BLOCK_COUNT, 0)
        set(v) = sp.edit().putInt(KEY_BLOCK_COUNT, v).apply()

    /** JS flag map consumed by the cosmetic script. */
    fun sectionFlags(): Map<String, Boolean> =
        SECTION_KEYS.entries.associate { (key, js) -> js to sp.getBoolean(key, false) }

    fun nightMode(): Int = when (darkMode) {
        DARK_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        DARK_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
