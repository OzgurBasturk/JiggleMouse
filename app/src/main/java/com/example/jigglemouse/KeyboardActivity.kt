package com.example.jigglemouse

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.jigglemouse.databinding.ActivityKeyboardBinding

class KeyboardActivity : AppCompatActivity(), HidMouse.Listener {

    private lateinit var binding: ActivityKeyboardBinding
    private lateinit var hidKeyboard: HidKeyboard
    private var service: JiggleService? = null
    private var deviceList: List<BluetoothDevice> = emptyList()
    private var selectedLayout = KeyboardLayout.US_QWERTY

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as JiggleService.LocalBinder).getService()
            // Free up the HID slot from the mouse before we register the keyboard.
            service?.releaseForKeyboardMode()
            hidKeyboard = HidKeyboard(applicationContext, this@KeyboardActivity)
            hidKeyboard.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.keyboardStatus.text = getString(R.string.status_starting)

        val layoutLabels = listOf(getString(R.string.layout_us), getString(R.string.layout_tr))
        binding.keyboardLayoutSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, layoutLabels)
        binding.keyboardLayoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLayout = if (position == 1) KeyboardLayout.TURKISH_Q else KeyboardLayout.US_QWERTY
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadPairedDevices()

        binding.btnKeyboardConnect.setOnClickListener {
            val index = binding.keyboardDeviceSpinner.selectedItemPosition
            if (index in deviceList.indices) hidKeyboard.connectTo(deviceList[index])
        }

        binding.keyboardInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // A single character was typed (soft keyboard commits one at a time in this flow).
                if (count == 1 && before == 0 && s != null) {
                    val c = s[start]
                    val mapped = if (selectedLayout == KeyboardLayout.TURKISH_Q)
                        HidKeyboard.turkishQCharToKey(c) else HidKeyboard.usQwertyCharToKey(c)
                    mapped?.let { (usage, shift) -> hidKeyboard.sendKey(usage, shift) }
                }
            }
            override fun afterTextChanged(s: Editable?) {
                // Clear immediately so each keystroke is captured in isolation above.
                if (s != null && s.isNotEmpty()) s.clear()
            }
        })

        binding.btnBackspace.setOnClickListener { hidKeyboard.sendKey(HidKeyboard.KEY_BACKSPACE) }
        binding.btnEnter.setOnClickListener { hidKeyboard.sendKey(HidKeyboard.KEY_ENTER) }

        bindService(Intent(this, JiggleService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun loadPairedDevices() {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            val bonded = bm.adapter?.bondedDevices?.toList() ?: emptyList()
            deviceList = bonded
            val labels = bonded.map { try { it.name ?: it.address } catch (e: SecurityException) { it.address } }
            binding.keyboardDeviceSpinner.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        } catch (e: SecurityException) {
            binding.keyboardStatus.text = getString(R.string.missing_paired_permission)
        }
    }

    // --- HidMouse.Listener (reused interface; keyboard has no mouse movement, only these callbacks) ---

    override fun onRegistered() {
        runOnUiThread { binding.keyboardStatus.text = getString(R.string.keyboard_ready) }
    }

    override fun onUnregistered() {
        runOnUiThread { binding.keyboardStatus.text = getString(R.string.status_starting) }
    }

    override fun onConnectionState(device: BluetoothDevice?, connected: Boolean) {
        runOnUiThread {
            val name = try { device?.name ?: device?.address ?: "device" } catch (e: SecurityException) { "device" }
            binding.keyboardStatus.text = if (connected)
                getString(R.string.keyboard_connected, name) else getString(R.string.disconnected)
        }
    }

    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        if (::hidKeyboard.isInitialized) hidKeyboard.stop()
        // Hand the HID slot back to the mouse.
        service?.reclaimMouseMode()
        try { unbindService(serviceConnection) } catch (e: Exception) { /* not bound */ }
        super.onDestroy()
    }
}
