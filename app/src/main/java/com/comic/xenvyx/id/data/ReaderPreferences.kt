package com.comic.xenvyx.id.data

import android.content.Context

/** Small API-21-compatible preference store. Secrets and website cookies are never stored here. */
class ReaderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("reader_preferences", Context.MODE_PRIVATE)

    enum class ThemeChoice { LIGHT, DARK, SYSTEM }
    enum class ReadingMode { CONTINUOUS, TRADITIONAL, WEBSITE_DEFAULT }

    var theme: ThemeChoice
        get() = runCatching { ThemeChoice.valueOf(prefs.getString(KEY_THEME, ThemeChoice.SYSTEM.name)!!) }
            .getOrDefault(ThemeChoice.SYSTEM)
        set(value) { prefs.edit().putString(KEY_THEME, value.name).apply() }

    var readingMode: ReadingMode
        get() = runCatching { ReadingMode.valueOf(prefs.getString(KEY_MODE, ReadingMode.CONTINUOUS.name)!!) }
            .getOrDefault(ReadingMode.CONTINUOUS)
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    var backupReminderDismissed: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_DISMISSED, false)
        set(value) { prefs.edit().putBoolean(KEY_REMINDER_DISMISSED, value).apply() }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_MODE = "reading_mode"
        private const val KEY_REMINDER_DISMISSED = "backup_reminder_dismissed"
    }
}
