package com.example.notifilter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {

    private lateinit var appPriorityManager: AppPriorityManager
    private lateinit var feedbackHelper: FeedbackHelper
    private var alarmPlayer: MediaPlayer? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == Intent.ACTION_POWER_DISCONNECTED) {
                if (appPriorityManager.isAlarmEnabled("anti_theft")) {
                    playAlarm()
                }
            } else if (action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                
                if (isCharging && level >= 80 && appPriorityManager.isAlarmEnabled("battery_saver")) {
                    // One-time alert for 80%
                    feedbackHelper.playLowPriorityFeedback()
                }
            } else if (action == Intent.ACTION_POWER_CONNECTED) {
                stopAlarm()
            }
        }
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
        registerReceiver(batteryReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        stopAlarm()
    }

    private fun playAlarm() {
        if (alarmPlayer == null) {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            alarmPlayer = MediaPlayer.create(this, alarmUri)
            alarmPlayer?.isLooping = true
            alarmPlayer?.start()
        }
    }

    private fun stopAlarm() {
        alarmPlayer?.stop()
        alarmPlayer?.release()
        alarmPlayer = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        
        val pm = packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val logEntry = NotificationLog(
            packageName = packageName,
            appName = appName,
            timestamp = System.currentTimeMillis(),
            content = if (title.isNotEmpty()) "$title: $text" else text
        )
        appPriorityManager.addLog(logEntry)

        if (packageName == this.packageName) return

        val config = appPriorityManager.getConfig(packageName)
        if (config.isEnabled) {
            feedbackHelper.playFeedback(config)
        } else {
            feedbackHelper.playLowPriorityFeedback()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
