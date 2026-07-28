package com.notifylter.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.notifylter.app.R
import java.util.ArrayDeque
import kotlin.math.abs

class ChargerFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var tvPower: TextView
    private lateinit var tvRating: TextView
    private lateinit var tvTip: TextView
    private lateinit var tvPeak: TextView
    private lateinit var tvLevel: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvHealth: TextView
    private lateinit var tvTimeToFull: TextView
    private lateinit var progressCurrent: CircularProgressIndicator
    private lateinit var switchKeepScreenOn: SwitchMaterial
    private lateinit var switchAntiTheft: SwitchMaterial
    private lateinit var switchBatterySaver: SwitchMaterial
    private lateinit var btnResetPeak: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private var batteryManager: BatteryManager? = null

    // Charging current fluctuates second-to-second; smooth recent samples so the
    // rating reflects sustained charge speed rather than a single noisy reading.
    private val recentSamples = ArrayDeque<Long>()
    private var peakMa = 0L
    private var defaultStatColor = 0

    companion object {
        private const val SMOOTHING_WINDOW = 5
        private const val POLL_INTERVAL_MS = 1000L
        private const val TEMP_WARNING_C = 40.0

        // Readings above this are assumed to be in µA rather than mA (device-dependent).
        private const val MICROAMP_THRESHOLD = 10_000L

        // Full-scale current for the dial, roughly the ceiling of common fast chargers.
        private const val GAUGE_FULL_SCALE_MA = 3000.0

        // Sustained charge current (mA) boundaries for each quality tier.
        private const val TIER_ULTRA_MA = 1800
        private const val TIER_FAST_MA = 1200
        private const val TIER_STANDARD_MA = 700
        private const val TIER_SLOW_MA = 300

        private const val COLOR_NEUTRAL = 0xFF666666.toInt()
        private const val COLOR_HOT = 0xFFF44336.toInt()
        private const val COLOR_ULTRA = 0xFF4CAF50.toInt()
        private const val COLOR_FAST = 0xFF8BC34A.toInt()
        private const val COLOR_STANDARD = 0xFF2196F3.toInt()
        private const val COLOR_SLOW = 0xFFFF9800.toInt()
        private const val COLOR_POOR = 0xFFF44336.toInt()
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateBatteryInfo()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_charger, container, false)
        val main = requireActivity() as MainActivity

        tvStatus = view.findViewById(R.id.tvStatus)
        tvCurrent = view.findViewById(R.id.tvCurrent)
        tvVoltage = view.findViewById(R.id.tvVoltage)
        tvPower = view.findViewById(R.id.tvPower)
        tvRating = view.findViewById(R.id.tvRating)
        tvTip = view.findViewById(R.id.tvTip)
        tvPeak = view.findViewById(R.id.tvPeak)
        tvLevel = view.findViewById(R.id.tvLevel)
        tvTemp = view.findViewById(R.id.tvTemp)
        tvHealth = view.findViewById(R.id.tvHealth)
        tvTimeToFull = view.findViewById(R.id.tvTimeToFull)
        progressCurrent = view.findViewById(R.id.progressCurrent)
        switchKeepScreenOn = view.findViewById(R.id.switchKeepScreenOn)
        switchAntiTheft = view.findViewById(R.id.switchAntiTheft)
        switchBatterySaver = view.findViewById(R.id.switchBatterySaver)
        btnResetPeak = view.findViewById(R.id.btnResetPeak)
        defaultStatColor = tvTemp.currentTextColor

        val prefs = main.appPriorityManager
        switchKeepScreenOn.isChecked = prefs.isFlagEnabled(AppPriorityManager.KEY_KEEP_SCREEN_ON)
        switchAntiTheft.isChecked = prefs.isFlagEnabled(AppPriorityManager.KEY_ANTI_THEFT)
        switchBatterySaver.isChecked = prefs.isFlagEnabled(AppPriorityManager.KEY_BATTERY_SAVER)

        switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            prefs.setFlag(AppPriorityManager.KEY_KEEP_SCREEN_ON, isChecked)
            applyKeepScreenOn(isChecked)
        }
        switchAntiTheft.setOnCheckedChangeListener { _, isChecked ->
            prefs.setFlag(AppPriorityManager.KEY_ANTI_THEFT, isChecked)
        }
        switchBatterySaver.setOnCheckedChangeListener { _, isChecked ->
            prefs.setFlag(AppPriorityManager.KEY_BATTERY_SAVER, isChecked)
        }
        btnResetPeak.setOnClickListener {
            resetSession()
            tvPeak.text = getString(R.string.charger_dashes)
        }

        batteryManager = requireContext().getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return view
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn(switchKeepScreenOn.isChecked)
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
        applyKeepScreenOn(false)
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        val window = activity?.window ?: return
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun resetSession() {
        recentSamples.clear()
        peakMa = 0L
    }

    private fun updateBatteryInfo() {
        if (!isAdded) return
        val intent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageV = voltage / 1000.0

        var currentNow = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
        if (abs(currentNow) > MICROAMP_THRESHOLD) {
            currentNow /= 1000
        }
        val rawMa = abs(currentNow)

        // Smooth the instantaneous reading and track the session peak.
        if (isCharging) {
            recentSamples.addLast(rawMa)
            while (recentSamples.size > SMOOTHING_WINDOW) recentSamples.removeFirst()
            if (rawMa > peakMa) peakMa = rawMa
        } else {
            resetSession()
        }
        val smoothedMa = if (recentSamples.isEmpty()) rawMa
            else recentSamples.sum() / recentSamples.size

        tvStatus.text = getString(if (isCharging) R.string.charger_state_charging else R.string.charger_state_discharging)
        tvCurrent.text = if (smoothedMa > 0) smoothedMa.toString() else getString(R.string.charger_dashes)
        tvVoltage.text = getString(R.string.charger_voltage_format, voltageV)

        // Power delivered to the battery (W) = battery voltage (V) x current (A).
        val powerW = voltageV * (smoothedMa / 1000.0)
        tvPower.text = getString(R.string.charger_power_format, powerW)

        val tempC = updateStats(intent, isCharging, smoothedMa)

        val progressPercent = ((smoothedMa / GAUGE_FULL_SCALE_MA) * 100).toInt().coerceIn(0, 100)
        progressCurrent.setProgress(progressPercent, true)

        if (!isCharging) {
            applyRating(R.string.charger_rating_not_charging, R.string.charger_tip_not_charging, COLOR_NEUTRAL)
            return
        }

        when {
            smoothedMa >= TIER_ULTRA_MA    -> applyRating(R.string.charger_rating_ultra,    R.string.charger_tip_ultra,    COLOR_ULTRA)
            smoothedMa >= TIER_FAST_MA     -> applyRating(R.string.charger_rating_fast,     R.string.charger_tip_fast,     COLOR_FAST)
            smoothedMa >= TIER_STANDARD_MA -> applyRating(R.string.charger_rating_standard, R.string.charger_tip_standard, COLOR_STANDARD)
            smoothedMa >= TIER_SLOW_MA     -> applyRating(R.string.charger_rating_slow,     R.string.charger_tip_slow,     COLOR_SLOW)
            else                           -> applyRating(R.string.charger_rating_poor,     R.string.charger_tip_poor,     COLOR_POOR)
        }

        // A hot battery matters more than charge speed, so this has to come after
        // applyRating — otherwise the rating tip overwrites the warning.
        if (!tempC.isNaN() && tempC >= TEMP_WARNING_C) {
            tvTip.setText(R.string.charger_temp_warning)
        }
    }

    /** Fills the live-stats rows and returns the battery temperature in °C, or NaN if unknown. */
    private fun updateStats(intent: Intent?, isCharging: Boolean, currentMa: Long): Double {
        tvPeak.text = if (peakMa > 0) getString(R.string.charger_peak_format, peakMa)
            else getString(R.string.charger_dashes)

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        tvLevel.text = if (levelPct >= 0) getString(R.string.charger_level_format, levelPct)
            else getString(R.string.charger_dashes)

        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else Double.NaN
        if (!tempC.isNaN()) {
            tvTemp.text = getString(R.string.charger_temp_format, tempC)
            tvTemp.setTextColor(if (tempC >= TEMP_WARNING_C) COLOR_HOT else defaultStatColor)
        } else {
            tvTemp.text = getString(R.string.charger_dashes)
            tvTemp.setTextColor(defaultStatColor)
        }

        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        tvHealth.setText(healthLabel(health))

        tvTimeToFull.text = estimateTimeToFull(isCharging, levelPct, currentMa)
            ?: getString(R.string.charger_dashes)

        return tempC
    }

    private fun estimateTimeToFull(isCharging: Boolean, levelPct: Int, currentMa: Long): String? {
        if (!isCharging || currentMa <= 0 || levelPct !in 1..99) return null
        val chargeCounterUah = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0L
        if (chargeCounterUah <= 0) return null
        val chargeMah = chargeCounterUah / 1000.0
        val fullMah = chargeMah * 100.0 / levelPct
        val remainingMah = fullMah - chargeMah
        if (remainingMah <= 0) return null
        val totalMinutes = (remainingMah / currentMa * 60.0).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) getString(R.string.charger_eta_format, hours, minutes)
            else getString(R.string.charger_eta_minutes_format, minutes)
    }

    private fun healthLabel(health: Int): Int = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> R.string.charger_health_good
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.charger_health_overheat
        BatteryManager.BATTERY_HEALTH_DEAD -> R.string.charger_health_dead
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.charger_health_over_voltage
        BatteryManager.BATTERY_HEALTH_COLD -> R.string.charger_health_cold
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.charger_health_failure
        else -> R.string.charger_health_unknown
    }

    private fun applyRating(ratingRes: Int, tipRes: Int, color: Int) {
        tvRating.setText(ratingRes)
        tvRating.setTextColor(color)
        tvTip.setText(tipRes)
        progressCurrent.setIndicatorColor(color)
    }
}
