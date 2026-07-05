package com.example.jigglemouse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.jigglemouse.databinding.ActivityKeyboardBinding

class KeyboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeyboardBinding
    private var service: JiggleService? = null
    private var selectedLayout = KeyboardLayout.US_QWERTY

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as JiggleService.LocalBinder).getService()
            updateStatus()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val layoutLabels = listOf(getString(R.string.layout_us), getString(R.string.layout_tr))
        binding.keyboardLayoutDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, layoutLabels)
        )
        binding.keyboardLayoutDropdown.setText(layoutLabels[0], false)
        binding.keyboardLayoutDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedLayout = if (position == 1) KeyboardLayout.TURKISH_Q else KeyboardLayout.US_QWERTY
        }

        binding.keyboardInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (count == 1 && before == 0 && s != null) {
                    val c = s[start]
                    val mapped = if (selectedLayout == KeyboardLayout.TURKISH_Q)
                        HidCombo.turkishQCharToKey(c) else HidCombo.usQwertyCharToKey(c)
                    mapped?.let { (usage, shift) -> service?.hidCombo?.sendKey(usage, shift) }
                }
            }
            override fun afterTextChanged(s: Editable?) {
                if (s != null && s.isNotEmpty()) s.clear()
            }
        })

        binding.btnBackspace.setOnClickListener { it.pulseOnce(); service?.hidCombo?.sendKey(HidCombo.KEY_BACKSPACE) }
        binding.btnEnter.setOnClickListener { it.pulseOnce(); service?.hidCombo?.sendKey(HidCombo.KEY_ENTER) }

        bindService(Intent(this, JiggleService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        binding.root.fadeInUp()
    }

    private fun updateStatus() {
        val connected = service?.isConnected == true
        binding.keyboardStatus.text = getString(
            if (connected) R.string.keyboard_ready_connected else R.string.trackpad_not_connected
        )
        binding.keyboardInput.isEnabled = connected
        binding.btnBackspace.isEnabled = connected
        binding.btnEnter.isEnabled = connected
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        try { unbindService(serviceConnection) } catch (e: Exception) { /* not bound */ }
        super.onDestroy()
    }
}
