package com.example.jigglemouse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.jigglemouse.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: JiggleService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = (binder as JiggleService.LocalBinder).getService()
            service = local
            local.setStatusListener(statusListener)
            binding.jiggleSwitch.isEnabled = local.isConnected
            binding.jiggleSwitch.isChecked = local.isJiggling
            highlightMode(local.jiggleMode)
            binding.statusText.text = when {
                local.isConnected -> getString(R.string.connected_simple)
                local.isRegistered -> getString(R.string.ready_pick_device)
                else -> getString(R.string.status_starting)
            }
            binding.btnConnectQuick.text = if (local.lastKnownDeviceAddress != null)
                getString(R.string.main_reconnect) else getString(R.string.main_connect_a_device)
            updateRunningIndicator()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val statusListener = object : JiggleService.StatusListener {
        override fun onRegistered() {
            runOnUiThread { binding.statusText.text = getString(R.string.ready_pick_device) }
        }
        override fun onUnregistered() {
            runOnUiThread { binding.statusText.text = getString(R.string.hid_service_stopped) }
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
                    R.string.last_jiggle, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
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

        // Check onboarding status directly via prefs (no service binding needed
        // yet) so we can redirect immediately on first ever launch.
        val prefs = getSharedPreferences("jigglemouse_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("onboarding_done", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnOpenTrackpad.setOnClickListener { startActivity(Intent(this, TrackpadActivity::class.java)) }
        binding.btnOpenKeyboard.setOnClickListener { startActivity(Intent(this, KeyboardActivity::class.java)) }

        binding.btnConnectQuick.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.lastKnownDeviceAddress != null) {
                binding.statusText.text = getString(R.string.connecting)
                svc.connectToLastDevice()
            } else {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        binding.jiggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            service?.setJiggleEnabled(isChecked)
            if (!isChecked) {
                binding.lastJiggleText.text = ""
                updateRunningIndicator()
            }
        }
        binding.modeBtnSilent.setOnClickListener { setMode(JiggleMode.SILENT) }
        binding.modeBtnHuman.setOnClickListener { setMode(JiggleMode.HUMAN_LIKE) }
        binding.modeBtnActive.setOnClickListener { setMode(JiggleMode.ACTIVE_WORK) }

        val intent = Intent(this, JiggleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setMode(mode: JiggleMode) {
        service?.setJiggleMode(mode)
        highlightMode(mode)
        updateRunningIndicator()
    }

    private fun highlightMode(mode: JiggleMode) {
        binding.modeBtnSilent.alpha = if (mode == JiggleMode.SILENT) 1f else 0.5f
        binding.modeBtnHuman.alpha = if (mode == JiggleMode.HUMAN_LIKE) 1f else 0.5f
        binding.modeBtnActive.alpha = if (mode == JiggleMode.ACTIVE_WORK) 1f else 0.5f
    }

    private fun updateRunningIndicator() {
        val svc = service ?: return
        if (!::binding.isInitialized) return
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

    override fun onResume() {
        super.onResume()
        updateRunningIndicator()
    }

    override fun onDestroy() {
        service?.setStatusListener(null)
        if (::binding.isInitialized && service != null) unbindService(serviceConnection)
        super.onDestroy()
    }
}
