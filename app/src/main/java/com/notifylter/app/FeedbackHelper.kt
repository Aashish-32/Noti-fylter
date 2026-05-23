package com.notifylter.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

class FeedbackHelper(private val context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraId: String? = findFlashCameraId()

    private val flashThread = HandlerThread("FeedbackHelper-Flash").apply { start() }
    private val flashHandler = Handler(flashThread.looper)
    // Bumped on each request so an in-flight flash sequence can detect it's stale and abort.
    private val flashGeneration = AtomicInteger(0)

    private fun findFlashCameraId(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            null
        }
    }

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
            playNotificationSound()
        }

        // 3. Flash
        if (config.useFlash) {
            flashLight()
        }
    }

    fun vibrate(pattern: LongArray) {
        if (!vibrator.hasVibrator()) return
        if (pattern.isEmpty() || pattern.all { it == 0L }) return
        
        vibrator.cancel() // Clear any existing vibration first

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use ALARM usage to ensure it doesn't get cut off by system notification logic
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .build()
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), audioAttributes)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    fun cancel() {
        if (vibrator.hasVibrator()) {
            vibrator.cancel()
        }
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
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    private fun playNotificationSound() {
        val notificationUri = try {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } catch (e: Exception) {
            null
        } ?: return

        val player = try {
            MediaPlayer.create(context, notificationUri)
        } catch (e: Exception) {
            null
        } ?: return

        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { mp, _, _ ->
            mp.release()
            true
        }
        try {
            player.start()
        } catch (e: IllegalStateException) {
            player.release()
        }
    }

    private fun flashLight() {
        val id = cameraId ?: return
        // Invalidate any in-flight flash sequence so it stops on its next tick,
        // then queue ours. Serialized on the single flash handler thread.
        val generation = flashGeneration.incrementAndGet()
        flashHandler.removeCallbacksAndMessages(null)
        flashHandler.post {
            try {
                // Ensure torch is off in case a stale sequence left it on.
                cameraManager.setTorchMode(id, false)
                for (i in 1..3) {
                    if (flashGeneration.get() != generation) return@post
                    cameraManager.setTorchMode(id, true)
                    Thread.sleep(100)
                    cameraManager.setTorchMode(id, false)
                    if (i < 3) Thread.sleep(100)
                }
            } catch (e: Exception) {
                try { cameraManager.setTorchMode(id, false) } catch (_: Exception) {}
            }
        }
    }
}
