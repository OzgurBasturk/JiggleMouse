package com.example.jigglemouse

import android.app.AlertDialog
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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.jigglemouse.databinding.ActivitySettingsBinding
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var service: JiggleService? = null

    private val knownDevices = LinkedHashMap<String, BluetoothDevice>()
    private var deviceList: List<BluetoothDevice> = emptyList()
    private var profiles: List<DeviceProfile> = emptyList()
    private var presetNames: List<String> = emptyList()

    private var scheduleStartMin = 9 * 60
    private var scheduleEndMin = 18 * 60

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
                    refreshDeviceSpinner()
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as JiggleService.LocalBinder).getService()
            bindServiceState()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupConnectionSection()
        setupJiggleSection()
        setupScheduleSection()
        setupIdentitySection()
        setupAppearanceSection()
        setupDangerZone()

        registerReceiver(discoveryReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })

        bindService(Intent(this, JiggleService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun bindServiceState() {
        val svc = service ?: return
        binding.autoReconnectSwitch.isChecked = svc.isAutoReconnectEnabled
        binding.minIntervalInput.setText(svc.minIntervalSeconds.toString())
        binding.maxIntervalInput.setText(svc.maxIntervalSeconds.toString())
        binding.jiggleModeGroup.check(
            when (svc.jiggleMode) {
                JiggleMode.HUMAN_LIKE -> R.id.mode_human
                JiggleMode.ACTIVE_WORK -> R.id.mode_active
                JiggleMode.SILENT -> R.id.mode_silent
            }
        )
        scheduleStartMin = svc.scheduleStartMin
        scheduleEndMin = svc.scheduleEndMin
        binding.scheduleSwitch.isChecked = svc.scheduleEnabled
        binding.scheduleDetails.visibility = if (svc.scheduleEnabled) View.VISIBLE else View.GONE
        binding.scheduleWeekdaysCheckbox.isChecked = svc.scheduleWeekdaysOnly
        updateScheduleButtonLabels()
        updateCurrentNameText()
        loadPairedDevices()
        refreshProfilesSpinner()
    }

    // --- Connection & profiles -----------------------------------------

    private fun setupConnectionSection() {
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnConnect.setOnClickListener { connectToSelected() }
        binding.autoReconnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            service?.setAutoReconnectEnabled(isChecked)
        }
        binding.btnConnectProfile.setOnClickListener { connectToSelectedProfile() }
        binding.btnDeleteProfile.setOnClickListener { deleteSelectedProfile() }
        binding.btnSaveProfile.setOnClickListener { saveCurrentAsProfile() }
    }

    private fun loadPairedDevices() {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            val bonded = bm.adapter?.bondedDevices?.toList() ?: emptyList()
            bonded.forEach { knownDevices[it.address] = it }
            refreshDeviceSpinner()
        } catch (e: SecurityException) { }
    }

    private fun refreshDeviceSpinner() {
        val devices = knownDevices.values.toList()
        val labels = devices.map { d -> try { d.name ?: d.address } catch (e: SecurityException) { d.address } }
        binding.deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        deviceList = devices
    }

    private fun startScan() {
        val bm = getSystemService(BluetoothManager::class.java)
        val adapter = bm.adapter ?: return
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            binding.btnScan.isEnabled = false
            binding.btnScan.text = getString(R.string.scanning_button)
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.missing_scan_permission), Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectToSelected() {
        val index = binding.deviceSpinner.selectedItemPosition
        if (index < 0 || index >= deviceList.size) {
            Toast.makeText(this, getString(R.string.no_device_selected), Toast.LENGTH_SHORT).show()
            return
        }
        service?.connectTo(deviceList[index])
        Toast.makeText(this, getString(R.string.connecting), Toast.LENGTH_SHORT).show()
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
        val profile = profiles.getOrNull(index) ?: return
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            val device = bm.adapter?.getRemoteDevice(profile.address) ?: return
            service?.connectTo(device)
            Toast.makeText(this, getString(R.string.connecting), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { }
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
        if (name.isBlank()) return
        service?.saveProfile(name, device.address)
        binding.profileNameInput.setText("")
        Toast.makeText(this, getString(R.string.profile_saved_toast, name), Toast.LENGTH_SHORT).show()
        refreshProfilesSpinner()
    }

    // --- Jiggle behavior ---------------------------------------------

    private fun setupJiggleSection() {
        binding.jiggleModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.mode_human -> JiggleMode.HUMAN_LIKE
                R.id.mode_active -> JiggleMode.ACTIVE_WORK
                else -> JiggleMode.SILENT
            }
            service?.setJiggleMode(mode)
        }
        binding.btnApplyInterval.setOnClickListener {
            val min = binding.minIntervalInput.text.toString().toIntOrNull()
            val max = binding.maxIntervalInput.text.toString().toIntOrNull()
            if (min == null || max == null) {
                Toast.makeText(this, getString(R.string.invalid_interval), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            service?.setIntervalSeconds(min, max)
            Toast.makeText(this, "✓", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Schedule -------------------------------------------------------

    private fun setupScheduleSection() {
        binding.scheduleSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.scheduleDetails.visibility = if (isChecked) View.VISIBLE else View.GONE
            pushSchedule()
        }
        binding.scheduleWeekdaysCheckbox.setOnCheckedChangeListener { _, _ -> pushSchedule() }
        binding.btnScheduleStart.setOnClickListener { pickTime(isStart = true) }
        binding.btnScheduleEnd.setOnClickListener { pickTime(isStart = false) }
    }

    private fun pickTime(isStart: Boolean) {
        val current = if (isStart) scheduleStartMin else scheduleEndMin
        TimePickerDialog(this, { _, hour, minute ->
            val total = hour * 60 + minute
            if (isStart) scheduleStartMin = total else scheduleEndMin = total
            updateScheduleButtonLabels()
            pushSchedule()
        }, current / 60, current % 60, true).show()
    }

    private fun updateScheduleButtonLabels() {
        binding.btnScheduleStart.text = getString(R.string.schedule_start) + ": " + formatMinutes(scheduleStartMin)
        binding.btnScheduleEnd.text = getString(R.string.schedule_end) + ": " + formatMinutes(scheduleEndMin)
    }

    private fun formatMinutes(totalMin: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", totalMin / 60, totalMin % 60)

    private fun pushSchedule() {
        service?.setSchedule(
            binding.scheduleSwitch.isChecked, scheduleStartMin, scheduleEndMin,
            binding.scheduleWeekdaysCheckbox.isChecked
        )
    }

    // --- Identity ---------------------------------------------------

    private fun setupIdentitySection() {
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
            } else presetNames.getOrNull(position) ?: return@setOnClickListener
            if (name.isBlank()) return@setOnClickListener
            val ok = service?.spoofDeviceName(name) ?: false
            Toast.makeText(
                this,
                if (ok) getString(R.string.name_applied_toast, name) else getString(R.string.missing_name_permission),
                Toast.LENGTH_SHORT
            ).show()
            updateCurrentNameText()
        }
        binding.btnRestoreName.setOnClickListener {
            service?.restoreDeviceName()
            Toast.makeText(this, getString(R.string.name_restored_toast), Toast.LENGTH_SHORT).show()
            updateCurrentNameText()
        }
    }

    private fun updateCurrentNameText() {
        val name = service?.currentAdapterName ?: return
        binding.currentNameText.text = getString(R.string.current_name_prefix, name)
    }

    // --- Appearance ----------------------------------------------------

    private fun setupAppearanceSection() {
        binding.btnThemeSystem.setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        binding.btnThemeLight.setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        binding.btnThemeDark.setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        binding.btnLangEn.setOnClickListener {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        binding.btnLangTr.setOnClickListener {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("tr"))
        }
    }

    // --- Danger zone ------------------------------------------------

    private fun setupDangerZone() {
        binding.btnRedoSetup.setOnClickListener {
            service?.setOnboardingComplete(false)
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
        binding.btnStopEverything.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.stop_everything_confirm_title)
                .setMessage(R.string.stop_everything_confirm_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    service?.stopEverything()
                    finishAffinity()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(discoveryReceiver) } catch (e: Exception) { }
        if (service != null) unbindService(serviceConnection)
        super.onDestroy()
    }
}
