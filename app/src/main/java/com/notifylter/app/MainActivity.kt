package com.notifylter.app

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.*
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.widget.*
import androidx.activity.OnBackPressedCallback
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
import com.notifylter.app.R
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private companion object {
        const val STATE_NAV_ITEM_ID = "selected_nav_item_id"

        /** Upper bound for a single hold while recording a custom pattern. */
        const val MAX_RECORD_BUZZ_MS = 5000L
    }

    lateinit var appPriorityManager: AppPriorityManager
    lateinit var feedbackHelper: FeedbackHelper
    private lateinit var drawerLayout: DrawerLayout
    private var selectedNavItemId = R.id.nav_notifications

    override fun onCreate(savedInstanceState: Bundle?) {
        appPriorityManager = AppPriorityManager(this)

        // Apply the saved theme before any view is inflated; doing it after
        // setContentView makes the activity recreate itself and flash the wrong theme.
        AppCompatDelegate.setDefaultNightMode(
            if (appPriorityManager.isDarkMode()) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        feedbackHelper = FeedbackHelper(this)

        drawerLayout = findViewById(R.id.drawerLayout)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val navigationView = findViewById<NavigationView>(R.id.navigationView)
        navigationView.setNavigationItemSelectedListener { item ->
            // Re-selecting the current destination would tear down and rebuild the same
            // fragment (and reload the whole app list) for nothing.
            if (item.itemId != selectedNavItemId) {
                val fragment = when (item.itemId) {
                    R.id.nav_notifications -> AppsFragment()
                    R.id.nav_insights -> InsightsFragment()
                    R.id.nav_history -> HistoryFragment()
                    R.id.nav_charger -> ChargerFragment()
                    else -> null
                }
                if (fragment != null) {
                    selectedNavItemId = item.itemId
                    item.isChecked = true
                    replaceFragment(fragment)
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        selectedNavItemId = savedInstanceState?.getInt(STATE_NAV_ITEM_ID, R.id.nav_notifications)
            ?: R.id.nav_notifications
        navigationView.setCheckedItem(selectedNavItemId)

        if (savedInstanceState == null) {
            replaceFragment(AppsFragment())
        }

        // An open drawer should absorb the back gesture instead of leaving the screen.
        val closeDrawerOnBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
        onBackPressedDispatcher.addCallback(this, closeDrawerOnBack)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) { closeDrawerOnBack.isEnabled = true }
            override fun onDrawerClosed(drawerView: View) { closeDrawerOnBack.isEnabled = false }
        })

        checkBatteryOptimization()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_NAV_ITEM_ID, selectedNavItemId)
    }

    override fun onDestroy() {
        if (::feedbackHelper.isInitialized) feedbackHelper.release()
        super.onDestroy()
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.settings) { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
                // Some OEM builds ship without this settings screen.
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.toast_battery_optimization_manual, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    // --- Shared Dialog Logic ---

    fun showSettingsDialog(appItem: AppItem, onSaved: () -> Unit = {}) {
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
        tvStart.text = getString(R.string.start_time_format, currentConfig.scheduleStartHour, currentConfig.scheduleStartMinute)
        tvEnd.text = getString(R.string.end_time_format, currentConfig.scheduleEndHour, currentConfig.scheduleEndMinute)

        // Built from FeedbackHelper so the list can never drift from the actual patterns.
        val patterns = feedbackHelper.patternNames
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, patterns)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVibration.adapter = spinnerAdapter
        spinnerVibration.setSelection(patterns.indexOf(currentConfig.vibrationPattern).coerceAtLeast(0))

        spinnerVibration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var isInitialSelection = true
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                val selectedPattern = patterns[position]
                val isCustom = selectedPattern == FeedbackHelper.CUSTOM_PATTERN
                btnRecord.visibility = if (isCustom) View.VISIBLE else View.GONE
                if (!isInitialSelection) {
                    // Preview the selection so the user can hear/feel it before saving.
                    val preview = if (isCustom) currentConfig.customVibration
                    else feedbackHelper.presetPatterns[selectedPattern]
                    preview?.let { feedbackHelper.vibrate(it) }
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

        dialogView.findViewById<Button>(R.id.btnAppNotificationSettings).setOnClickListener {
            openAppNotificationSettings(appItem.packageName)
        }

        switchSchedule.setOnCheckedChangeListener { _, isChecked -> 
            currentConfig = currentConfig.copy(scheduleActive = isChecked)
            layoutSchedule.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        tvStart.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> 
                currentConfig = currentConfig.copy(scheduleStartHour = h, scheduleStartMinute = m)
                tvStart.text = getString(R.string.start_time_format, h, m)
            }, currentConfig.scheduleStartHour, currentConfig.scheduleStartMinute, true).show()
        }

        tvEnd.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> 
                currentConfig = currentConfig.copy(scheduleEndHour = h, scheduleEndMinute = m)
                tvEnd.text = getString(R.string.end_time_format, h, m)
            }, currentConfig.scheduleEndHour, currentConfig.scheduleEndMinute, true).show()
        }

        btnSave.setOnClickListener {
            val selectedPattern = spinnerVibration.selectedItem?.toString() ?: FeedbackHelper.DEFAULT_PATTERN
            // "Custom" with nothing recorded would silently fall back to the default
            // pattern, so say so rather than letting the user think it was saved.
            if (selectedPattern == FeedbackHelper.CUSTOM_PATTERN && currentConfig.customVibration == null) {
                Toast.makeText(this, R.string.toast_record_custom_first, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val finalConfig = currentConfig.copy(
                isEnabled = switchEnabled.isChecked,
                vibrationPattern = selectedPattern,
                useFlash = switchFlash.isChecked,
                useSound = switchSound.isChecked,
                scheduleActive = switchSchedule.isChecked
            )
            appPriorityManager.setConfig(appItem.packageName, finalConfig)
            appItem.config = finalConfig
            dialog.dismiss()
            onSaved()
        }
        dialog.setOnDismissListener { feedbackHelper.cancel() }
        dialog.show()
    }

    // Opens the system notification settings for the given app so the user can turn off
    // its own vibration, which otherwise clashes with NotiFilter's replayed pattern.
    private fun openAppNotificationSettings(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData("package:$packageName".toUri())
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.toast_app_notification_settings_manual, Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showRecorderDialog(onSaved: (LongArray) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_record_vibration, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        val touchArea = view.findViewById<View>(R.id.vibrationTouchArea)
        // Alternating gap/buzz durations, which is exactly the waveform format.
        val timings = mutableListOf<Long>()
        var lastActionTime = 0L
        touchArea.setOnTouchListener { _, event ->
            val now = System.currentTimeMillis()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    timings.add(if (lastActionTime != 0L) now - lastActionTime else 0L)
                    lastActionTime = now
                    feedbackHelper.vibrate(longArrayOf(0, MAX_RECORD_BUZZ_MS))
                }
                // ACTION_CANCEL fires when the gesture is stolen (e.g. a scroll takes over);
                // without it the 5s hold buzz would keep running.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    timings.add(now - lastActionTime)
                    lastActionTime = now
                    feedbackHelper.cancel()
                }
            }
            true
        }
        view.findViewById<Button>(R.id.btnResetRecording).setOnClickListener {
            timings.clear()
            lastActionTime = 0L
            feedbackHelper.cancel()
        }
        view.findViewById<Button>(R.id.btnSaveRecording).setOnClickListener {
            val recorded = timings.toLongArray()
            // Dismiss before handing the pattern back, so this dialog's cleanup doesn't
            // cancel the confirmation buzz the caller plays.
            dialog.dismiss()
            // A lone leading gap is not a pattern — need at least one buzz.
            if (recorded.size >= 2) {
                onSaved(recorded)
            } else {
                Toast.makeText(this, R.string.toast_recording_empty, Toast.LENGTH_SHORT).show()
            }
        }
        // Stop the hold buzz if the dialog goes away mid-gesture.
        dialog.setOnDismissListener { feedbackHelper.cancel() }
        dialog.show()
    }

    // --- Internal Fragments ---

    class AppsFragment : Fragment() {
        private companion object {
            const val AUTO_SET_PATTERN = "Heartbeat"
            val MESSAGING_PACKAGES = setOf(
                "com.whatsapp",
                "com.viber.voip",
                "org.mattermost",
                "com.slack",
                "org.telegram.messenger",
                "com.facebook.orca"
            )
        }

        private var recyclerView: RecyclerView? = null
        private var adapter: AppAdapter? = null
        private val appList = mutableListOf<AppItem>()

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val view = inflater.inflate(R.layout.fragment_apps, container, false)
            val main = requireActivity() as MainActivity

            val switchDarkMode = view.findViewById<SwitchMaterial>(R.id.switchDarkMode)
            switchDarkMode.isChecked = main.appPriorityManager.isDarkMode()
            switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
                main.appPriorityManager.setDarkMode(isChecked)
                AppCompatDelegate.setDefaultNightMode(if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }

            val btnPermission = view.findViewById<Button>(R.id.btnPermission)
            btnPermission.setOnClickListener {
                try {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        startActivity(intent)
                    } catch (e2: Exception) {
                        try {
                            val intent = Intent()
                            intent.setClassName("com.android.settings", "com.android.settings.Settings\$NotificationAppListActivity")
                            startActivity(intent)
                        } catch (e3: Exception) {
                            Toast.makeText(requireContext(), R.string.toast_notification_access_manual, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            val btnAutoSet = view.findViewById<Button>(R.id.btnAutoSet)
            btnAutoSet.setOnClickListener {
                val changed = mutableListOf<Int>()
                appList.forEachIndexed { index, item ->
                    if (!item.config.isEnabled && item.packageName in MESSAGING_PACKAGES) {
                        val newConfig = item.config.copy(isEnabled = true, vibrationPattern = AUTO_SET_PATTERN)
                        main.appPriorityManager.setConfig(item.packageName, newConfig)
                        item.config = newConfig
                        changed.add(index)
                    }
                }
                if (changed.isNotEmpty()) {
                    changed.forEach { adapter?.notifyItemChanged(it) }
                    Toast.makeText(requireContext(), getString(R.string.toast_auto_set_configured, changed.size), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.toast_auto_set_none, Toast.LENGTH_SHORT).show()
                }
            }

            // Bound to the (initially empty) backing list up front so nothing depends on
            // the background load having finished.
            val list = view.findViewById<RecyclerView>(R.id.recyclerViewApps)
            val appAdapter = AppAdapter(appList, main.appPriorityManager) { position, item ->
                main.showSettingsDialog(item) {
                    // Reflect an enable/disable made inside the dialog on the row itself.
                    adapter?.notifyItemChanged(position)
                }
            }
            list.layoutManager = LinearLayoutManager(context)
            list.adapter = appAdapter
            list.setHasFixedSize(true)

            // Animation for the list
            val animation = AnimationUtils.loadAnimation(context, android.R.anim.fade_in)
            val controller = LayoutAnimationController(animation)
            controller.delay = 0.1f
            controller.order = LayoutAnimationController.ORDER_NORMAL
            list.layoutAnimation = controller

            recyclerView = list
            adapter = appAdapter

            loadInstalledApps(main)
            return view
        }

        override fun onDestroyView() {
            super.onDestroyView()
            // Drop the view references — the adapter holds app icons for every installed app.
            recyclerView?.adapter = null
            recyclerView = null
            adapter = null
        }

        private fun loadInstalledApps(main: MainActivity) {
            val pm = main.applicationContext.packageManager
            val priorityManager = main.appPriorityManager
            val uiHandler = Handler(Looper.getMainLooper())

            Thread({
                val loaded = mutableListOf<AppItem>()
                try {
                    // Query launcher activities directly. getInstalledApplications is subject
                    // to Android 11+ package-visibility filtering and would return a
                    // partial list; this matches the <queries> declaration in the manifest.
                    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val resolved = pm.queryIntentActivities(launcherIntent, 0)
                    val seen = HashSet<String>()
                    for (info in resolved) {
                        val activityInfo = info.activityInfo ?: continue
                        val packageName = activityInfo.packageName ?: continue
                        // An app can expose several launcher entries; show it once.
                        if (!seen.add(packageName)) continue
                        // Application label, not the activity label, so an app reads the same
                        // here as it does in History and Insights.
                        val name = activityInfo.applicationInfo.loadLabel(pm).toString()
                        val icon = info.loadIcon(pm)
                        val config = priorityManager.getConfig(packageName)
                        loaded.add(AppItem(name, packageName, icon, config))
                    }
                    loaded.sortBy { it.name.lowercase() }
                } catch (_: Exception) {
                    // Best-effort — surface what we managed to gather.
                }

                uiHandler.post {
                    if (!isAdded) return@post
                    appList.clear()
                    appList.addAll(loaded)
                    adapter?.notifyDataSetChanged()
                    recyclerView?.scheduleLayoutAnimation()
                }
            }, "AppsFragment-Load").start()
        }
    }

    class HistoryFragment : Fragment() {
        private var recyclerView: RecyclerView? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val view = inflater.inflate(R.layout.fragment_history, container, false)
            val main = requireActivity() as MainActivity
            val list = view.findViewById<RecyclerView>(R.id.recyclerViewHistory)
            list.layoutManager = LinearLayoutManager(context)

            // Animation for history
            val animation = AnimationUtils.loadAnimation(context, android.R.anim.fade_in)
            val controller = LayoutAnimationController(animation)
            controller.delay = 0.1f
            controller.order = LayoutAnimationController.ORDER_NORMAL
            list.layoutAnimation = controller
            recyclerView = list

            view.findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
                main.appPriorityManager.clearLogs()
                updateLogs(main)
            }
            updateLogs(main)
            return view
        }

        override fun onResume() {
            super.onResume()
            // Notifications may have arrived while this screen was backgrounded.
            (activity as? MainActivity)?.let { updateLogs(it) }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            recyclerView?.adapter = null
            recyclerView = null
        }

        private fun updateLogs(main: MainActivity) {
            val list = recyclerView ?: return
            list.adapter = HistoryAdapter(main.appPriorityManager.getLogs())
            list.scheduleLayoutAnimation()
        }
    }
}

// Adapters
class AppAdapter(
    private val items: List<AppItem>,
    private val mgr: AppPriorityManager,
    private val onClick: (position: Int, item: AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_app, p, false))

    override fun onBindViewHolder(h: ViewHolder, p: Int) {
        val item = items[p]
        h.name.text = item.name
        h.packageName.text = item.packageName
        h.icon.setImageDrawable(item.icon)

        // Detach the recycled row's listener before restoring state, otherwise setting
        // isChecked fires the previous row's callback and saves to the wrong package.
        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = item.config.isEnabled
        h.check.setOnCheckedChangeListener { _, isChecked ->
            val bound = h.bindingAdapterPosition
            if (bound == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
            val target = items[bound]
            target.config = target.config.copy(isEnabled = isChecked)
            mgr.setConfig(target.packageName, target.config)
        }

        h.itemView.setOnClickListener {
            val bound = h.bindingAdapterPosition
            if (bound != RecyclerView.NO_POSITION) onClick(bound, items[bound])
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val name: TextView = v.findViewById(R.id.appName)
        val packageName: TextView = v.findViewById(R.id.appPackage)
        val check: CheckBox = v.findViewById(R.id.chkPriority)
    }
}

class HistoryAdapter(private val items: List<NotificationLog>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    // History spans up to 100 entries, which can reach back days — a bare clock time
    // would be ambiguous, so only today's entries omit the date.
    private val startOfToday: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_history, p, false))
    override fun onBindViewHolder(h: ViewHolder, p: Int) {
        val item = items[p]
        val date = Date(item.timestamp)
        h.name.text = item.appName
        h.time.text = if (item.timestamp >= startOfToday) timeFormat.format(date) else dateTimeFormat.format(date)
        h.content.text = item.content
    }
    override fun getItemCount() = items.size
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.historyAppName)
        val time: TextView = v.findViewById(R.id.historyTime)
        val content: TextView = v.findViewById(R.id.historyContent)
    }
}
