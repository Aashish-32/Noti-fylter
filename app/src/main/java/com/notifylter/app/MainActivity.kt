package com.notifylter.app

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.*
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.example.notifilter.R
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    lateinit var appPriorityManager: AppPriorityManager
    lateinit var feedbackHelper: FeedbackHelper
    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appPriorityManager = AppPriorityManager(this)
        feedbackHelper = FeedbackHelper(this)

        if (appPriorityManager.isDarkMode()) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        drawerLayout = findViewById(R.id.drawerLayout)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val navigationView = findViewById<NavigationView>(R.id.navigationView)
        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_notifications -> replaceFragment(AppsFragment())
                R.id.nav_insights -> replaceFragment(InsightsFragment())
                R.id.nav_history -> replaceFragment(HistoryFragment())
                R.id.nav_charger -> replaceFragment(ChargerFragment())
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        if (savedInstanceState == null) {
            replaceFragment(AppsFragment())
        }

        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("To ensure NotiFilter works reliably in the background, please disable battery optimization for this app.")
                    .setPositiveButton("Settings") { _, _ ->
                        intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        intent.data = "package:$packageName".toUri()
                        startActivity(intent)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    // --- Shared Dialog Logic ---

    fun showSettingsDialog(appItem: AppItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_settings, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val switchEnabled = dialogView.findViewById<SwitchMaterial>(R.id.switchEnabled)
        val spinnerVibration = dialogView.findViewById<Spinner>(R.id.spinnerVibration)
        val btnRecord = dialogView.findViewById<Button>(R.id.btnRecordVibration)
        val switchFlash = dialogView.findViewById<SwitchMaterial>(R.id.switchFlash)
        val switchSound = dialogView.findViewById<SwitchMaterial>(R.id.switchSound)
        val switchSchedule = dialogView.findViewById<SwitchMaterial>(R.id.switchSchedule)
        val layoutSchedule = dialogView.findViewById<View>(R.id.layoutScheduleTimes)
        val tvStart = dialogView.findViewById<TextView>(R.id.tvStartTime)
        val tvEnd = dialogView.findViewById<TextView>(R.id.tvEndTime)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)

        var currentConfig = appItem.config.copy()

        switchEnabled.isChecked = currentConfig.isEnabled
        switchFlash.isChecked = currentConfig.useFlash
        switchSound.isChecked = currentConfig.useSound
        switchSchedule.isChecked = currentConfig.scheduleActive
        layoutSchedule.visibility = if (currentConfig.scheduleActive) View.VISIBLE else View.GONE
        tvStart.text = String.format("Start: %02d:%02d", currentConfig.scheduleStartHour, currentConfig.scheduleStartMinute)
        tvEnd.text = String.format("End: %02d:%02d", currentConfig.scheduleEndHour, currentConfig.scheduleEndMinute)

        val patterns = arrayOf("Pulse", "Double Pulse", "Triple Short", "Long", "Heartbeat", "Rapid", "Zig-Zag", "SOS", "Staccato", "Custom")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, patterns)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVibration.adapter = spinnerAdapter
        spinnerVibration.setSelection(patterns.indexOf(currentConfig.vibrationPattern).coerceAtLeast(0))

        spinnerVibration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var isInitialSelection = true
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                val selectedPattern = patterns[position]
                btnRecord.visibility = if (selectedPattern == "Custom") View.VISIBLE else View.GONE
                if (!isInitialSelection && selectedPattern != "Custom") {
                    feedbackHelper.presetPatterns[selectedPattern]?.let { feedbackHelper.vibrate(it) }
                }
                isInitialSelection = false
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        btnRecord.setOnClickListener { 
            showRecorderDialog { recorded -> 
                currentConfig = currentConfig.copy(customVibration = recorded) 
                feedbackHelper.vibrate(recorded)
            } 
        }
        
        switchSchedule.setOnCheckedChangeListener { _, isChecked -> 
            currentConfig = currentConfig.copy(scheduleActive = isChecked)
            layoutSchedule.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        tvStart.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> 
                currentConfig = currentConfig.copy(scheduleStartHour = h, scheduleStartMinute = m)
                tvStart.text = String.format("Start: %02d:%02d", h, m)
            }, currentConfig.scheduleStartHour, currentConfig.scheduleStartMinute, true).show()
        }

        tvEnd.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> 
                currentConfig = currentConfig.copy(scheduleEndHour = h, scheduleEndMinute = m)
                tvEnd.text = String.format("End: %02d:%02d", h, m)
            }, currentConfig.scheduleEndHour, currentConfig.scheduleEndMinute, true).show()
        }

        btnSave.setOnClickListener {
            val finalConfig = currentConfig.copy(
                isEnabled = switchEnabled.isChecked,
                vibrationPattern = spinnerVibration.selectedItem.toString(),
                useFlash = switchFlash.isChecked,
                useSound = switchSound.isChecked,
                scheduleActive = switchSchedule.isChecked
            )
            appPriorityManager.setConfig(appItem.packageName, finalConfig)
            appItem.config = finalConfig
            dialog.dismiss()
        }
        dialog.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showRecorderDialog(onSaved: (LongArray) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_record_vibration, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        val touchArea = view.findViewById<View>(R.id.vibrationTouchArea)
        val timings = mutableListOf<Long>()
        var lastActionTime = 0L
        touchArea.setOnTouchListener { _, event ->
            val now = System.currentTimeMillis()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (lastActionTime != 0L) timings.add(now - lastActionTime) else timings.add(0L)
                    lastActionTime = now
                    feedbackHelper.vibrate(longArrayOf(0, 5000))
                }
                MotionEvent.ACTION_UP -> {
                    timings.add(now - lastActionTime)
                    lastActionTime = now
                    feedbackHelper.cancel()
                }
            }
            true
        }
        view.findViewById<Button>(R.id.btnResetRecording).setOnClickListener { timings.clear(); lastActionTime = 0L }
        view.findViewById<Button>(R.id.btnSaveRecording).setOnClickListener {
            if (timings.isNotEmpty()) onSaved(timings.toLongArray())
            dialog.dismiss()
        }
        dialog.show()
    }

    // --- Internal Fragments ---

    class AppsFragment : Fragment() {
        private lateinit var recyclerView: RecyclerView
        private lateinit var adapter: AppAdapter
        private val highPriorityApps = setOf("com.whatsapp", "com.viber.voip", "org.mattermost", "com.slack", "org.telegram.messenger", "com.facebook.orca")
        private val appList = mutableListOf<AppItem>()

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val view = inflater.inflate(R.layout.fragment_apps, container, false)
            val main = activity as MainActivity

            val switchDarkMode = view.findViewById<SwitchMaterial>(R.id.switchDarkMode)
            switchDarkMode.isChecked = main.appPriorityManager.isDarkMode()
            switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
                main.appPriorityManager.setDarkMode(isChecked)
                AppCompatDelegate.setDefaultNightMode(if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }

            val btnPermission = view.findViewById<Button>(R.id.btnPermission)
            btnPermission.setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }

            val btnAutoSet = view.findViewById<Button>(R.id.btnAutoSet)
            btnAutoSet.setOnClickListener {
                var count = 0
                for (item in appList) {
                    if (!item.config.isEnabled && highPriorityApps.contains(item.packageName)) {
                        val newConfig = item.config.copy(isEnabled = true, vibrationPattern = "Heartbeat")
                        main.appPriorityManager.setConfig(item.packageName, newConfig)
                        item.config = newConfig
                        count++
                    }
                }
                if (count > 0) {
                    adapter.notifyDataSetChanged()
                    Toast.makeText(context, "Configured $count messaging apps", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No new messaging apps to configure", Toast.LENGTH_SHORT).show()
                }
            }

            recyclerView = view.findViewById(R.id.recyclerViewApps)
            recyclerView.layoutManager = LinearLayoutManager(context)
            
            // Animation for the list
            val animation = AnimationUtils.loadAnimation(context, android.R.anim.fade_in)
            val controller = LayoutAnimationController(animation)
            controller.delay = 0.1f
            controller.order = LayoutAnimationController.ORDER_NORMAL
            recyclerView.layoutAnimation = controller

            loadInstalledApps(main)
            return view
        }

        private fun loadInstalledApps(main: MainActivity) {
            val pm = requireContext().packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            appList.clear()

            for (app in packages) {
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    val name = app.loadLabel(pm).toString()
                    val icon = app.loadIcon(pm)
                    val config = main.appPriorityManager.getConfig(app.packageName)
                    appList.add(AppItem(name, app.packageName, icon, config))
                }
            }

            adapter = AppAdapter(appList, main.appPriorityManager) { main.showSettingsDialog(it) }
            recyclerView.adapter = adapter
            recyclerView.scheduleLayoutAnimation()
        }
    }

    class HistoryFragment : Fragment() {
        private lateinit var recyclerView: RecyclerView
        private lateinit var adapter: HistoryAdapter

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val view = inflater.inflate(R.layout.fragment_history, container, false)
            val main = activity as MainActivity
            recyclerView = view.findViewById(R.id.recyclerViewHistory)
            recyclerView.layoutManager = LinearLayoutManager(context)
            
            // Animation for history
            val animation = AnimationUtils.loadAnimation(context, android.R.anim.fade_in)
            val controller = LayoutAnimationController(animation)
            controller.delay = 0.1f
            controller.order = LayoutAnimationController.ORDER_NORMAL
            recyclerView.layoutAnimation = controller

            view.findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
                main.appPriorityManager.clearLogs()
                updateLogs(main)
            }
            updateLogs(main)
            return view
        }

        private fun updateLogs(main: MainActivity) {
            adapter = HistoryAdapter(main.appPriorityManager.getLogs())
            recyclerView.adapter = adapter
            recyclerView.scheduleLayoutAnimation()
        }
    }
}

// Adapters
class AppAdapter(private val items: List<AppItem>, private val mgr: AppPriorityManager, private val onClick: (AppItem) -> Unit) :
    RecyclerView.Adapter<AppAdapter.ViewHolder>() {
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_app, p, false))
    override fun onBindViewHolder(h: ViewHolder, p: Int) {
        val item = items[p]
        h.name.text = item.name
        h.icon.setImageDrawable(item.icon)
        h.check.isChecked = item.config.isEnabled
        h.itemView.setOnClickListener { onClick(item) }
        h.check.setOnCheckedChangeListener { _, isChecked ->
            item.config = item.config.copy(isEnabled = isChecked)
            mgr.setConfig(item.packageName, item.config)
        }
    }
    override fun getItemCount() = items.size
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val name: TextView = v.findViewById(R.id.appName)
        val check: CheckBox = v.findViewById(R.id.chkPriority)
    }
}

class HistoryAdapter(private val items: List<NotificationLog>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_history, p, false))
    override fun onBindViewHolder(h: ViewHolder, p: Int) {
        val item = items[p]
        h.name.text = item.appName
        h.time.text = sdf.format(Date(item.timestamp))
        h.content.text = item.content
    }
    override fun getItemCount() = items.size
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.historyAppName)
        val time: TextView = v.findViewById(R.id.historyTime)
        val content: TextView = v.findViewById(R.id.historyContent)
    }
}
