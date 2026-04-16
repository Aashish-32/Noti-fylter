package com.notifylter.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
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
    val vibrationPattern: String = "Pulse",
    val customVibration: LongArray? = null,
    val useFlash: Boolean = true,
    val useSound: Boolean = true,
    val scheduleActive: Boolean = false,
    val scheduleStartHour: Int = 9,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 17,
    val scheduleEndMinute: Int = 0
)

data class NotificationLog(
    val packageName: String,
    val appName: String,
    val timestamp: Long,
    val content: String
)

class AppPriorityManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("notifilter_prefs", Context.MODE_PRIVATE)
    private val historyPrefs: SharedPreferences = appContext.getSharedPreferences("notifilter_history", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    fun isDarkMode(): Boolean {
        return prefs.getBoolean("dark_mode", false)
    }

    fun setAlarmEnabled(key: String, enabled: Boolean) {
        prefs.edit().putBoolean(key, enabled).apply()
    }

    fun isAlarmEnabled(key: String): Boolean {
        return prefs.getBoolean(key, false)
    }

    fun hasConfig(packageName: String): Boolean {
        return prefs.contains(packageName)
    }

    fun setConfig(packageName: String, config: FeedbackConfig) {
        val json = gson.toJson(config)
        prefs.edit().putString(packageName, json).apply()
    }

    fun getConfig(packageName: String): FeedbackConfig {
        return try {
            val json = prefs.getString(packageName, null)
            if (json != null) {
                gson.fromJson(json, FeedbackConfig::class.java)
            } else {
                val isHigh = try { prefs.getBoolean(packageName, false) } catch (e: Exception) { false }
                FeedbackConfig(isEnabled = isHigh)
            }
        } catch (e: Exception) {
            val isHigh = try { prefs.getBoolean(packageName, false) } catch (e: Exception) { false }
            FeedbackConfig(isEnabled = isHigh)
        }
    }

    fun addLog(log: NotificationLog) {
        val logs = getLogs().toMutableList()
        logs.add(0, log)
        if (logs.size > 100) logs.removeAt(logs.size - 1)
        val json = gson.toJson(logs)
        historyPrefs.edit().putString("logs", json).apply()
    }

    fun getLogs(): List<NotificationLog> {
        val json = historyPrefs.getString("logs", null) ?: return emptyList()
        val type = object : TypeToken<List<NotificationLog>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearLogs() {
        historyPrefs.edit().clear().apply()
    }
}
