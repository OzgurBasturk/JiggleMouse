package com.example.jigglemouse

import android.Manifest
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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.jigglemouse.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var service: JiggleService? = null
    private val knownDevices = LinkedHashMap<String, BluetoothDevice>()
    private var deviceList: List<BluetoothDevice> = emptyList()
    private var presetNames: List<String> = emptyList()

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
        if (results.values.all { it }) {
            binding.permissionsStatusText.text = getString(R.string.permissions_granted)
            startAndBindService()
        }
    }

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
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as JiggleService.LocalBinder).getService()
            updateCurrentNameText()
            loadPairedDevices()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val permsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (permsGranted) {
            binding.permissionsStatusText.text = getString(R.string.permissions_granted)
            startAndBindService()
        }
        binding.btnGrantPermissions.setOnClickListener { permissionLauncher.launch(requiredPermissions) }

        setupIdentityStep()

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnConnect.setOnClickListener { connectToSelected() }
        binding.btnFinishSetup.setOnClickListener { finishOnboarding() }
        binding.btnSkipForNow.setOnClickListener { finishOnboarding() }

        registerReceiver(discoveryReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })
    }

    private fun setupIdentityStep() {
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
    }

    private fun updateCurrentNameText() {
        val name = service?.currentAdapterName ?: return
        binding.currentNameText.text = getString(R.string.current_name_prefix, name)
    }

    private fun startAndBindService() {
        val intent = Intent(this, JiggleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun loadPairedDevices() {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            val bonded = bm.adapter?.bondedDevices?.toList() ?: emptyList()
            bonded.forEach { knownDevices[it.address] = it }
            refreshSpinner()
        } catch (e: SecurityException) { /* no permission yet */ }
    }

    private fun refreshSpinner() {
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
        binding.connectStatusText.text = getString(R.string.connecting)
        service?.connectTo(deviceList[index])
        service?.setStatusListener(object : JiggleService.StatusListener {
            override fun onConnectionState(deviceName: String, connected: Boolean) {
                runOnUiThread {
                    binding.connectStatusText.text = if (connected)
                        getString(R.string.connected_to_mouse, deviceName) else getString(R.string.disconnected)
                }
            }
        })
    }

    private fun finishOnboarding() {
        service?.setOnboardingComplete(true)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        try { unregisterReceiver(discoveryReceiver) } catch (e: Exception) { }
        if (service != null) { service?.setStatusListener(null); unbindService(serviceConnection) }
        super.onDestroy()
    }
}
