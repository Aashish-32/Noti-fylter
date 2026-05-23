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
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.notifylter.app.R
import java.util.*

class ChargerFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var tvRating: TextView
    private lateinit var tvTip: TextView
    private lateinit var progressCurrent: CircularProgressIndicator
    private lateinit var switchAntiTheft: SwitchMaterial
    private lateinit var switchBatterySaver: SwitchMaterial
    private val handler = Handler(Looper.getMainLooper())
    private var batteryManager: BatteryManager? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateBatteryInfo()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_charger, container, false)
        val main = activity as MainActivity
        
        tvStatus = view.findViewById(R.id.tvStatus)
        tvCurrent = view.findViewById(R.id.tvCurrent)
        tvVoltage = view.findViewById(R.id.tvVoltage)
        tvRating = view.findViewById(R.id.tvRating)
        tvTip = view.findViewById(R.id.tvTip)
        progressCurrent = view.findViewById(R.id.progressCurrent)
        switchAntiTheft = view.findViewById(R.id.switchAntiTheft)
        switchBatterySaver = view.findViewById(R.id.switchBatterySaver)
        
        switchAntiTheft.isChecked = main.appPriorityManager.isAlarmEnabled("anti_theft")
        switchBatterySaver.isChecked = main.appPriorityManager.isAlarmEnabled("battery_saver")
        
        switchAntiTheft.setOnCheckedChangeListener { _, isChecked -> 
            main.appPriorityManager.setAlarmEnabled("anti_theft", isChecked)
        }
        switchBatterySaver.setOnCheckedChangeListener { _, isChecked -> 
            main.appPriorityManager.setAlarmEnabled("battery_saver", isChecked)
        }
        
        batteryManager = requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return view
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun updateBatteryInfo() {
        if (!isAdded) return
        val intent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageV = voltage / 1000.0

        var currentNow = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
        if (Math.abs(currentNow) > 10000) {
            currentNow /= 1000
        }
        val currentMa = Math.abs(currentNow)

        tvStatus.text = getString(if (isCharging) R.string.charger_state_charging else R.string.charger_state_discharging)
        tvCurrent.text = if (currentMa > 0) currentMa.toString() else getString(R.string.charger_dashes)
        tvVoltage.text = getString(R.string.charger_voltage_format, voltageV)

        val progressPercent = ((currentMa / 3000.0) * 100).toInt().coerceIn(0, 100)
        progressCurrent.setProgress(progressPercent, true)

        if (!isCharging) {
            applyRating(R.string.charger_rating_not_charging, R.string.charger_tip_not_charging, 0xFF666666.toInt())
            return
        }

        when {
            currentMa >= 1800 -> applyRating(R.string.charger_rating_ultra,    R.string.charger_tip_ultra,    0xFF4CAF50.toInt())
            currentMa >= 1200 -> applyRating(R.string.charger_rating_fast,     R.string.charger_tip_fast,     0xFF8BC34A.toInt())
            currentMa >= 700  -> applyRating(R.string.charger_rating_standard, R.string.charger_tip_standard, 0xFF2196F3.toInt())
            currentMa >= 300  -> applyRating(R.string.charger_rating_slow,     R.string.charger_tip_slow,     0xFFFF9800.toInt())
            else              -> applyRating(R.string.charger_rating_poor,     R.string.charger_tip_poor,     0xFFF44336.toInt())
        }
    }

    private fun applyRating(ratingRes: Int, tipRes: Int, color: Int) {
        tvRating.setText(ratingRes)
        tvRating.setTextColor(color)
        tvTip.setText(tipRes)
        progressCurrent.setIndicatorColor(color)
    }
}
