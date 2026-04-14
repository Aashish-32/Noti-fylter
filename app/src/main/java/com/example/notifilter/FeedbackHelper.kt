package com.example.notifilter

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.Calendar

class FeedbackHelper(private val context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId: String? = try { cameraManager.cameraIdList[0] } catch (e: Exception) { null }

    val presetPatterns = mapOf(
        "Pulse" to longArrayOf(0, 500),
        "Double Pulse" to longArrayOf(0, 200, 100, 200),
        "Triple Short" to longArrayOf(0, 100, 50, 100, 50, 100),
        "Long" to longArrayOf(0, 1000),
        "Heartbeat" to longArrayOf(0, 100, 100, 300),
        "Rapid" to longArrayOf(0, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50),
        "Zig-Zag" to longArrayOf(0, 400, 100, 100, 100, 400),
        "SOS" to longArrayOf(0, 100, 100, 100, 100, 100, 100, 300, 100, 300, 100, 300, 100, 100, 100, 100, 100, 100),
        "Staccato" to longArrayOf(0, 50, 150, 50, 150, 50, 150, 50)
    )

    fun playFeedback(config: FeedbackConfig) {
        if (!config.isEnabled) {
            playLowPriorityFeedback()
            return
        }

        if (config.scheduleActive && !isWithinSchedule(config)) {
            playLowPriorityFeedback()
            return
        }

        // 1. Vibration
        val pattern = if (config.vibrationPattern == "Custom" && config.customVibration != null) {
            config.customVibration
        } else {
            presetPatterns[config.vibrationPattern] ?: presetPatterns["Pulse"]!!
        }

        vibrate(pattern)

        // 2. Sound
        if (config.useSound) {
            try {
                val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val mediaPlayer = MediaPlayer.create(context, notificationUri)
                mediaPlayer?.apply {
                    start()
                    setOnCompletionListener { it.release() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Flash
        if (config.useFlash) {
            flashLight()
        }
    }

    fun vibrate(pattern: LongArray) {
        if (pattern.isEmpty() || pattern.all { it == 0L }) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    fun cancel() {
        vibrator.cancel()
    }

    private fun isWithinSchedule(config: FeedbackConfig): Boolean {
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(Calendar.MINUTE)

        val currentTime = currentHour * 60 + currentMinute
        val startTime = config.scheduleStartHour * 60 + config.scheduleStartMinute
        val endTime = config.scheduleEndHour * 60 + config.scheduleEndMinute

        return if (startTime <= endTime) {
            currentTime in startTime..endTime
        } else {
            // Overnights (e.g., 22:00 to 07:00)
            currentTime >= startTime || currentTime <= endTime
        }
    }

    fun playLowPriorityFeedback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    private fun flashLight() {
        cameraId?.let { id ->
            Thread {
                try {
                    for (i in 1..3) {
                        cameraManager.setTorchMode(id, true)
                        Thread.sleep(100)
                        cameraManager.setTorchMode(id, false)
                        Thread.sleep(100)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }
    }
}
