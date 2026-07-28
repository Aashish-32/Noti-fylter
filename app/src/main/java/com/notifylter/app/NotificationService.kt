package com.notifylter.app

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat

class NotificationService : NotificationListenerService() {

    private companion object {
        /** Battery percentage at which the "stop charging" nudge fires. */
        const val BATTERY_ALERT_PERCENT = 80
    }

    private lateinit var appPriorityManager: AppPriorityManager
    private lateinit var feedbackHelper: FeedbackHelper
    private var alarmPlayer: MediaPlayer? = null
    private var flagListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var receiverRegistered = false

    private var hasAlertedForBattery = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED -> {
                    hasAlertedForBattery = false
                    if (appPriorityManager.isFlagEnabled(AppPriorityManager.KEY_ANTI_THEFT)) {
                        playAlarm()
                    }
                }

                Intent.ACTION_POWER_CONNECTED -> stopAlarm()

                Intent.ACTION_BATTERY_CHANGED -> {
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                    // EXTRA_LEVEL is raw and only a percentage when EXTRA_SCALE is 100,
                    // which is not guaranteed — normalize before comparing.
                    val percent = batteryPercent(intent)
                    if (percent < 0) return

                    if (isCharging && percent >= BATTERY_ALERT_PERCENT && !hasAlertedForBattery &&
                        appPriorityManager.isFlagEnabled(AppPriorityManager.KEY_BATTERY_SAVER)
                    ) {
                        // One-time alert per charge session
                        feedbackHelper.playLowPriorityFeedback()
                        hasAlertedForBattery = true
                    } else if (percent < BATTERY_ALERT_PERCENT) {
                        hasAlertedForBattery = false
                    }
                }
            }
        }
    }

    private fun batteryPercent(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return level * 100 / scale
    }

    override fun onCreate() {
        super.onCreate()
        appPriorityManager = AppPriorityManager(this)
        feedbackHelper = FeedbackHelper(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // Protected system broadcasts — NOT_EXPORTED is correct on API 33+.
        ContextCompat.registerReceiver(this, batteryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

        // Without this, a looping anti-theft alarm could only be silenced by plugging the
        // charger back in — turning the toggle off in the UI now stops it too.
        flagListener = appPriorityManager.registerFlagListener { key ->
            if (key == AppPriorityManager.KEY_ANTI_THEFT &&
                !appPriorityManager.isFlagEnabled(AppPriorityManager.KEY_ANTI_THEFT)
            ) {
                stopAlarm()
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered — nothing to undo.
            }
            receiverRegistered = false
        }
        flagListener?.let { appPriorityManager.unregisterFlagListener(it) }
        flagListener = null
        stopAlarm()
        feedbackHelper.release()
        super.onDestroy()
    }

    private fun playAlarm() {
        if (alarmPlayer != null) return
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        val attributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val player = try {
            MediaPlayer.create(this, alarmUri, null, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
        } catch (e: Exception) {
            null
        } ?: return

        alarmPlayer = player
        try {
            player.isLooping = true
            player.start()
        } catch (e: IllegalStateException) {
            player.release()
            alarmPlayer = null
        }
    }

    private fun stopAlarm() {
        val player = alarmPlayer ?: return
        alarmPlayer = null
        try {
            if (player.isPlaying) player.stop()
        } catch (e: IllegalStateException) {
            // Already stopped or released.
        }
        player.release()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return // Ignore persistent notifications (media controls, etc.)

        val packageName = sbn.packageName
        // Our own notifications must not be logged or replayed — that would feed the app
        // its own output.
        if (packageName == this.packageName) return

        // A group summary duplicates its children; replaying both double-buzzes the user.
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        // getCharSequence, not getString: apps often set styled (Spannable) titles, which
        // getString silently returns null for.
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        val logEntry = NotificationLog(
            packageName = packageName,
            appName = resolveAppName(packageName),
            timestamp = System.currentTimeMillis(),
            content = if (title.isNotEmpty()) "$title: $text" else text
        )
        appPriorityManager.addLog(logEntry)

        val config = appPriorityManager.getConfig(packageName)
        if (config.isEnabled) {
            feedbackHelper.playFeedback(config)
        }
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
