package com.notifylter.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
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

    companion object {
        const val DEFAULT_PATTERN = "Pulse"

        /** Sentinel pattern name meaning "use [FeedbackConfig.customVibration]". */
        const val CUSTOM_PATTERN = "Custom"

        private const val FLASH_BLINKS = 3
        private const val FLASH_ON_MS = 100L
        private const val FLASH_OFF_MS = 100L
        private const val LOW_PRIORITY_MS = 100L

        /** VibrationEffect repeat index meaning "play the waveform once". */
        private const val NO_REPEAT = -1
    }

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

    // Insertion-ordered: the settings spinner is built from these keys, and the keys are
    // what gets persisted in FeedbackConfig, so don't rename one without a migration.
    val presetPatterns: Map<String, LongArray> = linkedMapOf(
        DEFAULT_PATTERN to longArrayOf(0, 500),
        "Double Pulse" to longArrayOf(0, 200, 100, 200),
        "Triple Short" to longArrayOf(0, 100, 50, 100, 50, 100),
        "Long" to longArrayOf(0, 1000),
        "Heartbeat" to longArrayOf(0, 100, 100, 300),
        "Rapid" to longArrayOf(0, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50),
        "Zig-Zag" to longArrayOf(0, 400, 100, 100, 100, 400),
        "SOS" to longArrayOf(0, 100, 100, 100, 100, 100, 100, 300, 100, 300, 100, 300, 100, 100, 100, 100, 100, 100),
        "Staccato" to longArrayOf(0, 50, 150, 50, 150, 50, 150, 50)
    )

    /** Selectable pattern names for the settings dialog, in display order. */
    val patternNames: List<String> = presetPatterns.keys.toList() + CUSTOM_PATTERN

    fun playFeedback(config: FeedbackConfig) {
        // Disabled apps and notifications outside the configured window get no feedback
        // at all — that is the point of the toggle and the schedule.
        if (!config.isEnabled) return
        if (config.scheduleActive && !isWithinSchedule(config)) return

        // 1. Vibration
        val pattern = if (config.vibrationPattern == CUSTOM_PATTERN && config.customVibration != null) {
            config.customVibration
        } else {
            presetPatterns[config.vibrationPattern] ?: presetPatterns.getValue(DEFAULT_PATTERN)
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
        // createWaveform rejects empty, all-zero and negative timings — a recorded custom
        // pattern is user-generated input, so validate rather than trust it.
        if (pattern.isEmpty() || pattern.all { it == 0L } || pattern.any { it < 0L }) return

        vibrator.cancel() // Clear any existing vibration first

        // Use ALARM usage to ensure it doesn't get cut off by system notification logic
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, NO_REPEAT), audioAttributes)
        } catch (e: IllegalArgumentException) {
            // Malformed pattern — nothing useful to play.
        }
    }

    fun cancel() {
        if (vibrator.hasVibrator()) {
            vibrator.cancel()
        }
    }

    /**
     * Stops all feedback and tears down the flash worker thread. Call from the owner's
     * teardown (`Activity.onDestroy` / `Service.onDestroy`); the helper is unusable after.
     */
    fun release() {
        cancel()
        flashGeneration.incrementAndGet()
        flashHandler.removeCallbacksAndMessages(null)
        cameraId?.let { id ->
            try {
                cameraManager.setTorchMode(id, false)
            } catch (e: Exception) {
                // Torch already released or camera unavailable.
            }
        }
        flashThread.quit()
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

    /** A single short buzz, used for app-level alerts such as the 80% charge notice. */
    fun playLowPriorityFeedback() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(LOW_PRIORITY_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun playNotificationSound() {
        val notificationUri = try {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } catch (e: Exception) {
            null
        } ?: return

        // Route through the notification stream rather than MediaPlayer.create's default
        // media stream, so the user's notification volume actually applies.
        val attributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        val player = try {
            MediaPlayer.create(context, notificationUri, null, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
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
                for (i in 1..FLASH_BLINKS) {
                    if (flashGeneration.get() != generation) return@post
                    cameraManager.setTorchMode(id, true)
                    Thread.sleep(FLASH_ON_MS)
                    cameraManager.setTorchMode(id, false)
                    if (i < FLASH_BLINKS) Thread.sleep(FLASH_OFF_MS)
                }
            } catch (e: Exception) {
                try { cameraManager.setTorchMode(id, false) } catch (_: Exception) {}
            }
        }
    }
}
