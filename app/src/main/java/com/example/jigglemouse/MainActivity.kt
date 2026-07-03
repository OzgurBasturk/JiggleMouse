package com.example.jigglemouse

import android.Manifest
import android.app.TimePickerDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.example.jigglemouse.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: JiggleService? = null

    private val knownDevices = LinkedHashMap<String, BluetoothDevice>()
    private var deviceList: List<BluetoothDevice> = emptyList()
    private var presetNames: List<String> = emptyList()
    private var profiles: List<DeviceProfile> = emptyList()

    private var scheduleStartMin = 9 * 60
    private var scheduleEndMin = 18 * 60

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_CONNECT
                perms += Manifest.permission.BLUETOOTH_SCAN
            } else {
                perms += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startAndBindService()
        else binding.statusText.text = getString(R.string.permission_denied)
    }

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateBatteryBanner() }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let { knownDevices[it.address] = it }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    binding.btnScan.isEnabled = true
                    binding.btnScan.text = getString(R.string.scan)
                    refreshSpinner()
                    binding.statusText.text = getString(R.string.scan_finished, knownDevices.size)
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = (binder as JiggleService.LocalBinder).getService()
            service = local
            local.setStatusListener(statusListener)
            binding.btnConnect.isEnabled = local.isRegistered
            binding.jiggleSwitch.isEnabled = local.isConnected
            binding.jiggleSwitch.isChecked = local.isJiggling
            binding.autoReconnectSwitch.isChecked = local.isAutoReconnectEnabled
            binding.minIntervalInput.setText(local.minIntervalSeconds.toString())
            binding.maxIntervalInput.setText(local.maxIntervalSeconds.toString())
            binding.jiggleModeGroup.check(
                when (local.jiggleMode) {
                    JiggleMode.HUMAN_LIKE -> R.id.mode_human
                    JiggleMode.ACTIVE_WORK -> R.id.mode_active
                    JiggleMode.SILENT -> R.id.mode_silent
                }
            )
            scheduleStartMin = local.scheduleStartMin
            scheduleEndMin = local.scheduleEndMin
            binding.scheduleSwitch.isChecked = local.scheduleEnabled
            binding.scheduleDetails.visibility = if (local.scheduleEnabled) View.VISIBLE else View.GONE
            binding.scheduleWeekdaysCheckbox.isChecked = local.scheduleWeekdaysOnly
            updateScheduleButtonLabels()
            binding.statusText.text = when {
                local.isConnected -> getString(R.string.connected_simple)
                local.isRegistered -> getString(R.string.ready_pick_device)
                else -> getString(R.string.status_starting)
            }
            updateCurrentNameText()
            refreshProfilesSpinner()
            updateRunningIndicator()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val statusListener = object : JiggleService.StatusListener {
        override fun onRegistered() {
            runOnUiThread {
                binding.statusText.text = getString(R.string.ready_pick_device)
                binding.btnConnect.isEnabled = true
            }
        }

        override fun onUnregistered() {
            runOnUiThread {
                binding.statusText.text = getString(R.string.hid_service_stopped)
                binding.btnConnect.isEnabled = false
            }
        }

        override fun onConnectionState(deviceName: String, connected: Boolean) {
            runOnUiThread {
                binding.statusText.text = if (connected)
                    getString(R.string.connected_to_mouse, deviceName) else getString(R.string.disconnected)
                binding.jiggleSwitch.isEnabled = connected
                if (!connected) {
                    binding.jiggleSwitch.isChecked = false
                    updateRunningIndicator()
                }
            }
        }

        override fun onError(message: String) {
            runOnUiThread { binding.statusText.text = getString(R.string.error_prefix, message) }
        }

        override fun onJiggled() {
            runOnUiThread {
                binding.lastJiggleText.text = getString(
                    R.string.last_jiggle,
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                )
                updateRunningIndicator()
            }
        }

        override fun onReconnecting(attempt: Int) {
            runOnUiThread { binding.statusText.text = getString(R.string.reconnecting, attempt) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupConnectionTab()
        setupJiggleTab()
        setupAdvancedTab()

        binding.btnLangEn.setOnClickListener { setAppLocale("en") }
        binding.btnLangTr.setOnClickListener { setAppLocale("tr") }

        registerReceiver(discoveryReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })

        ensurePermissionsThenInit()
        updateBatteryBanner()
    }

    // --- Tabs --------------------------------------------------------

    private fun setupTabs() {
        binding.tabBtnConnection.setOnClickListener { showTab(0) }
        binding.tabBtnJiggle.setOnClickListener { showTab(1) }
        binding.tabBtnAdvanced.setOnClickListener { showTab(2) }
        showTab(0)
    }

    private fun showTab(index: Int) {
        binding.tabContentConnection.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.tabContentJiggle.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.tabContentAdvanced.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    // --- Battery optimization -----------------------------------------

    private fun updateBatteryBanner() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempted = pm.isIgnoringBatteryOptimizations(packageName)
        binding.batteryBanner.visibility = if (exempted) View.GONE else View.VISIBLE
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            batteryOptLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Connection tab ------------------------------------------------

    private fun setupConnectionTab() {
        binding.btnConnect.isEnabled = false
        binding.btnConnect.setOnClickListener { connectToSelected() }
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnBatteryOpt.setOnClickListener { requestBatteryOptimizationExemption() }

        binding.autoReconnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            service?.setAutoReconnectEnabled(isChecked)
        }

        binding.btnOpenTrackpad.setOnClickListener {
            startActivity(Intent(this, TrackpadActivity::class.java))
        }
        binding.btnOpenKeyboard.setOnClickListener {
            startActivity(Intent(this, KeyboardActivity::class.java))
        }

        binding.btnConnectProfile.setOnClickListener { connectToSelectedProfile() }
        binding.btnDeleteProfile.setOnClickListener { deleteSelectedProfile() }
        binding.btnSaveProfile.setOnClickListener { saveCurrentAsProfile() }
    }

    private fun refreshProfilesSpinner() {
        profiles = service?.listProfiles() ?: emptyList()
        binding.noProfilesText.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        binding.profilesSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, profiles.map { it.name }
        )
    }

    private fun connectToSelectedProfile() {
        val index = binding.profilesSpinner.selectedItemPosition
        val profile = profiles.getOrNull(index) ?: run {
            Toast.makeText(this, getString(R.string.no_device_selected), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            val device = bm.adapter?.getRemoteDevice(profile.address) ?: return
            binding.statusText.text = getString(R.string.connecting)
            service?.connectTo(device)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteSelectedProfile() {
        val index = binding.profilesSpinner.selectedItemPosition
        val profile = profiles.getOrNull(index) ?: return
        service?.removeProfile(profile.address)
        refreshProfilesSpinner()
    }

    private fun saveCurrentAsProfile() {
        val index = binding.deviceSpinner.selectedItemPosition
        val device = deviceList.getOrNull(index)
        val name = binding.profileNameInput.text.toString().trim()
        if (device == null) {
            Toast.makeText(this, getString(R.string.no_device_selected), Toast.LENGTH_SHORT).show()
            return
        }
        if (name.isBlank()) {
            Toast.makeText(this, getString(R.string.profile_name_hint), Toast.LENGTH_SHORT).show()
            return
        }
        service?.saveProfile(name, device.address)
        binding.profileNameInput.setText("")
        Toast.makeText(this, getString(R.string.profile_saved_toast, name), Toast.LENGTH_SHORT).show()
        refreshProfilesSpinner()
    }

    // --- Jiggle tab ------------------------------------------------------

    private fun setupJiggleTab() {
        binding.jiggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!applyIntervalFromInputs()) {
                    binding.jiggleSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
            }
            service?.setJiggleEnabled(isChecked)
            if (!isChecked) {
                binding.lastJiggleText.text = ""
                updateRunningIndicator()
            }
        }
        binding.jiggleModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.mode_human -> JiggleMode.HUMAN_LIKE
                R.id.mode_active -> JiggleMode.ACTIVE_WORK
                else -> JiggleMode.SILENT
            }
            service?.setJiggleMode(mode)
            updateRunningIndicator()
        }

        binding.scheduleSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.scheduleDetails.visibility = if (isChecked) View.VISIBLE else View.GONE
            pushScheduleToService()
        }
        binding.scheduleWeekdaysCheckbox.setOnCheckedChangeListener { _, _ -> pushScheduleToService() }
        binding.btnScheduleStart.setOnClickListener { pickTime(isStart = true) }
        binding.btnScheduleEnd.setOnClickListener { pickTime(isStart = false) }
    }

    private fun pickTime(isStart: Boolean) {
        val current = if (isStart) scheduleStartMin else scheduleEndMin
        TimePickerDialog(this, { _, hour, minute ->
            val total = hour * 60 + minute
            if (isStart) scheduleStartMin = total else scheduleEndMin = total
            updateScheduleButtonLabels()
            pushScheduleToService()
        }, current / 60, current % 60, true).show()
    }

    private fun updateScheduleButtonLabels() {
        binding.btnScheduleStart.text = getString(R.string.schedule_start) + ": " + formatMinutes(scheduleStartMin)
        binding.btnScheduleEnd.text = getString(R.string.schedule_end) + ": " + formatMinutes(scheduleEndMin)
    }

    private fun formatMinutes(totalMin: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", totalMin / 60, totalMin % 60)

    private fun pushScheduleToService() {
        service?.setSchedule(
            binding.scheduleSwitch.isChecked,
            scheduleStartMin,
            scheduleEndMin,
            binding.scheduleWeekdaysCheckbox.isChecked
        )
        updateRunningIndicator()
    }

    private fun updateRunningIndicator() {
        val svc = service ?: return
        binding.runningIndicatorText.text = when {
            !svc.isJiggling -> ""
            svc.scheduleEnabled && !svc.isWithinSchedule() -> getString(R.string.currently_paused_schedule)
            else -> getString(R.string.currently_running, localizedModeLabel(svc.jiggleMode))
        }
    }

    private fun localizedModeLabel(mode: JiggleMode): String = when (mode) {
        JiggleMode.SILENT -> getString(R.string.mode_name_silent)
        JiggleMode.HUMAN_LIKE -> getString(R.string.mode_name_human)
        JiggleMode.ACTIVE_WORK -> getString(R.string.mode_name_active)
    }

    // --- Advanced tab ----------------------------------------------------

    private fun setupAdvancedTab() {
        presetNames = resources.getStringArray(R.array.preset_mouse_names).toList()
        val options = presetNames + getString(R.string.spoof_name_custom_option)
        binding.spoofNameSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        binding.spoofNameSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.spoofNameCustomInput.visibility =
                    if (position == options.lastIndex) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.btnApplyName.setOnClickListener {
            val position = binding.spoofNameSpinner.selectedItemPosition
            val name = if (position == options.lastIndex) {
                binding.spoofNameCustomInput.text.toString().trim()
            } else {
                presetNames.getOrNull(position) ?: return@setOnClickListener
            }
            if (name.isBlank()) {
                Toast.makeText(this, getString(R.string.spoof_name_custom_hint), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            service?.spoofDeviceName(name)
            Toast.makeText(this, getString(R.string.name_applied_toast, name), Toast.LENGTH_SHORT).show()
            updateCurrentNameText()
        }
        binding.btnRestoreName.setOnClickListener {
            service?.restoreDeviceName()
            Toast.makeText(this, getString(R.string.name_restored_toast), Toast.LENGTH_SHORT).show()
            updateCurrentNameText()
        }

        binding.btnThemeSystem.setOnClickListener { applyNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        binding.btnThemeLight.setOnClickListener { applyNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
        binding.btnThemeDark.setOnClickListener { applyNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
    }

    private fun applyNightMode(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun updateCurrentNameText() {
        val name = service?.currentAdapterName ?: return
        binding.currentNameText.text = getString(R.string.current_name_prefix, name)
    }

    // --- Setup / permissions ----------------------------------------------

    private fun ensurePermissionsThenInit() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAndBindService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startAndBindService() {
        val intent = Intent(this, JiggleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        loadPairedDevices()
    }

    private fun loadPairedDevices() {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            val bonded = bm.adapter?.bondedDevices?.toList() ?: emptyList()
            bonded.forEach { knownDevices[it.address] = it }
            refreshSpinner()
        } catch (e: SecurityException) {
            binding.statusText.text = getString(R.string.missing_paired_permission)
        }
    }

    private fun refreshSpinner() {
        val devices = knownDevices.values.toList()
        val bm = getSystemService(BluetoothManager::class.java)
        val bondedAddresses = try {
            bm.adapter?.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
        } catch (e: SecurityException) { emptySet() }

        val labels = devices.map { d ->
            val name = try { d.name ?: d.address } catch (e: SecurityException) { d.address }
            name + if (bondedAddresses.contains(d.address)) getString(R.string.paired_suffix) else getString(R.string.new_suffix)
        }
        binding.deviceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        deviceList = devices
    }

    private fun startScan() {
        val bm = getSystemService(BluetoothManager::class.java)
        val adapter = bm.adapter
        if (adapter == null) {
            Toast.makeText(this, getString(R.string.no_bluetooth_adapter), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            binding.btnScan.isEnabled = false
            binding.btnScan.text = getString(R.string.scanning_button)
            binding.statusText.text = getString(R.string.scanning_status)
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            binding.statusText.text = getString(R.string.missing_scan_permission)
        }
    }

    private fun connectToSelected() {
        val index = binding.deviceSpinner.selectedItemPosition
        if (index < 0 || index >= deviceList.size) {
            Toast.makeText(this, getString(R.string.no_device_selected), Toast.LENGTH_SHORT).show()
            return
        }
        binding.statusText.text = getString(R.string.connecting)
        service?.connectTo(deviceList[index])
    }

    private fun applyIntervalFromInputs(): Boolean {
        val minText = binding.minIntervalInput.text.toString()
        val maxText = binding.maxIntervalInput.text.toString()
        val min = minText.toIntOrNull()
        val max = maxText.toIntOrNull()
        if (min == null || max == null) {
            Toast.makeText(this, getString(R.string.invalid_interval), Toast.LENGTH_LONG).show()
            return false
        }
        service?.setIntervalSeconds(min, max)
        return true
    }

    private fun setAppLocale(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }

    override fun onResume() {
        super.onResume()
        updateBatteryBanner()
        updateRunningIndicator()
    }

    override fun onDestroy() {
        try { unregisterReceiver(discoveryReceiver) } catch (e: Exception) { /* already unregistered */ }
        service?.setStatusListener(null)
        if (service != null) unbindService(serviceConnection)
        super.onDestroy()
        // Note: JiggleService keeps running in the foreground after the
        // Activity is destroyed — that's the point. It only stops if the
        // user swipes it away from the notification or force-stops the app.
    }
}
