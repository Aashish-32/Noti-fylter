package com.notifylter.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var config: FeedbackConfig
)

data class FeedbackConfig(
    val isEnabled: Boolean = false,
    val vibrationPattern: String = FeedbackHelper.DEFAULT_PATTERN,
    val customVibration: LongArray? = null,
    val useFlash: Boolean = true,
    val useSound: Boolean = true,
    val scheduleActive: Boolean = false,
    val scheduleStartHour: Int = 9,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 17,
    val scheduleEndMinute: Int = 0
) {
    // A data class holding an array gets identity-based equals/hashCode from the
    // compiler, so two configs with equal patterns would compare unequal. Compare
    // the contents instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeedbackConfig) return false
        return isEnabled == other.isEnabled &&
            vibrationPattern == other.vibrationPattern &&
            customVibration.contentEquals(other.customVibration) &&
            useFlash == other.useFlash &&
            useSound == other.useSound &&
            scheduleActive == other.scheduleActive &&
            scheduleStartHour == other.scheduleStartHour &&
            scheduleStartMinute == other.scheduleStartMinute &&
            scheduleEndHour == other.scheduleEndHour &&
            scheduleEndMinute == other.scheduleEndMinute
    }

    override fun hashCode(): Int {
        var result = isEnabled.hashCode()
        result = 31 * result + vibrationPattern.hashCode()
        result = 31 * result + (customVibration?.contentHashCode() ?: 0)
        result = 31 * result + useFlash.hashCode()
        result = 31 * result + useSound.hashCode()
        result = 31 * result + scheduleActive.hashCode()
        result = 31 * result + scheduleStartHour
        result = 31 * result + scheduleStartMinute
        result = 31 * result + scheduleEndHour
        result = 31 * result + scheduleEndMinute
        return result
    }
}

data class NotificationLog(
    val packageName: String,
    val appName: String,
    val timestamp: Long,
    val content: String
)

class AppPriorityManager(context: Context) {

    companion object {
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_ANTI_THEFT = "anti_theft"
        const val KEY_BATTERY_SAVER = "battery_saver"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

        /** History is capped so the prefs blob stays small; oldest entries are evicted. */
        const val MAX_HISTORY_ENTRIES = 100

        private const val PREFS_NAME = "notifilter_prefs"
        private const val HISTORY_PREFS_NAME = "notifilter_history"
        private const val KEY_LOGS = "logs"

        // App-wide toggles share the config prefs file, so a package must never be
        // mistaken for one of them when reading or writing a FeedbackConfig.
        private val RESERVED_KEYS = setOf(KEY_DARK_MODE, KEY_ANTI_THEFT, KEY_BATTERY_SAVER, KEY_KEEP_SCREEN_ON)
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val historyPrefs: SharedPreferences = appContext.getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun setDarkMode(enabled: Boolean) {
        setFlag(KEY_DARK_MODE, enabled)
    }

    fun isDarkMode(): Boolean = isFlagEnabled(KEY_DARK_MODE)

    fun setFlag(key: String, enabled: Boolean) {
        prefs.edit { putBoolean(key, enabled) }
    }

    fun isFlagEnabled(key: String): Boolean {
        return prefs.getBoolean(key, false)
    }

    /**
     * Observes app-wide toggles. Lets the notification service react to a flag being
     * flipped in the UI (e.g. silencing a running anti-theft alarm) without polling.
     * The caller owns the returned listener and must pass it back to
     * [unregisterFlagListener] — SharedPreferences holds listeners weakly.
     */
    fun registerFlagListener(onChanged: (String) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) onChanged(key)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterFlagListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun setConfig(packageName: String, config: FeedbackConfig) {
        if (packageName in RESERVED_KEYS) return
        val json = gson.toJson(config)
        prefs.edit { putString(packageName, json) }
    }

    fun getConfig(packageName: String): FeedbackConfig {
        if (packageName in RESERVED_KEYS) return FeedbackConfig()
        return try {
            val json = prefs.getString(packageName, null)
            if (json != null) {
                gson.fromJson(json, FeedbackConfig::class.java) ?: legacyConfig(packageName)
            } else {
                legacyConfig(packageName)
            }
        } catch (e: Exception) {
            legacyConfig(packageName)
        }
    }

    /**
     * Pre-JSON installs stored a bare `isHigh` boolean per package. Read that shape so
     * older users keep their enabled apps after upgrading.
     */
    private fun legacyConfig(packageName: String): FeedbackConfig {
        val isHigh = try {
            prefs.getBoolean(packageName, false)
        } catch (e: ClassCastException) {
            false
        }
        return FeedbackConfig(isEnabled = isHigh)
    }

    // History is read on the UI thread and written from the binder thread that
    // delivers notifications — serialize the read-modify-write so entries don't drop.
    private val historyLock = Any()

    fun addLog(log: NotificationLog) {
        synchronized(historyLock) {
            val logs = readLogsLocked().toMutableList()
            logs.add(0, log)
            while (logs.size > MAX_HISTORY_ENTRIES) logs.removeAt(logs.size - 1)
            historyPrefs.edit { putString(KEY_LOGS, gson.toJson(logs)) }
        }
    }

    fun getLogs(): List<NotificationLog> = synchronized(historyLock) { readLogsLocked() }

    private fun readLogsLocked(): List<NotificationLog> {
        val json = historyPrefs.getString(KEY_LOGS, null) ?: return emptyList()
        val type = object : TypeToken<List<NotificationLog>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearLogs() {
        synchronized(historyLock) {
            historyPrefs.edit { clear() }
        }
    }
}
