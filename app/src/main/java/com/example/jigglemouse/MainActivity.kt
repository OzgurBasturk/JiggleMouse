package com.example.jigglemouse

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
    private var dotAnimator: ObjectAnimator? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = (binder as JiggleService.LocalBinder).getService()
            service = local
            local.setStatusListener(statusListener)
            binding.jiggleSwitch.isEnabled = local.isConnected
            binding.jiggleSwitch.isChecked = local.isJiggling
            highlightMode(local.jiggleMode)
            binding.intervalSlider.values = listOf(
                local.minIntervalSeconds.toFloat().coerceIn(1f, 300f),
                local.maxIntervalSeconds.toFloat().coerceIn(1f, 300f)
            )
            updateIntervalLabel(local.minIntervalSeconds, local.maxIntervalSeconds)
            setStatus(
                when {
                    local.isConnected -> getString(R.string.connected_simple)
                    local.isRegistered -> getString(R.string.ready_pick_device)
                    else -> getString(R.string.status_starting)
                },
                connected = local.isConnected
            )
            binding.btnConnectQuick.text = if (local.lastKnownDeviceAddress != null)
                getString(R.string.main_reconnect) else getString(R.string.main_connect_a_device)
            updateRunningIndicator()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val statusListener = object : JiggleService.StatusListener {
        override fun onRegistered() {
            runOnUiThread { setStatus(getString(R.string.ready_pick_device), connected = false) }
        }
        override fun onUnregistered() {
            runOnUiThread { setStatus(getString(R.string.hid_service_stopped), connected = false) }
        }
        override fun onConnectionState(deviceName: String, connected: Boolean) {
            runOnUiThread {
                setStatus(
                    if (connected) getString(R.string.connected_to_mouse, deviceName) else getString(R.string.disconnected),
                    connected
                )
                binding.jiggleSwitch.isEnabled = connected
                if (!connected) {
                    binding.jiggleSwitch.isChecked = false
                    updateRunningIndicator()
                }
            }
        }
        override fun onError(message: String) {
            runOnUiThread { setStatus(getString(R.string.error_prefix, message), connected = false) }
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
            runOnUiThread { setStatus(getString(R.string.reconnecting, attempt), connected = false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                setStatus(getString(R.string.connecting), connected = false)
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

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.mode_btn_human -> JiggleMode.HUMAN_LIKE
                R.id.mode_btn_active -> JiggleMode.ACTIVE_WORK
                else -> JiggleMode.SILENT
            }
            service?.setJiggleMode(mode)
            updateRunningIndicator()
        }

        binding.intervalSlider.addOnChangeListener { slider, _, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val values = slider.values.sorted()
            val min = values[0].toInt()
            val max = values[1].toInt()
            updateIntervalLabel(min, max)
            service?.setIntervalSeconds(min, max)
        }

        // Gentle fade-in on load instead of an abrupt appearance.
        binding.rootContent.alpha = 0f
        binding.rootContent.animate().alpha(1f).setDuration(280).start()

        val intent = Intent(this, JiggleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setStatus(text: String, connected: Boolean) {
        binding.statusText.text = text
        val color = if (connected) R.color.success else R.color.text_muted
        binding.statusDot.background.setTint(ContextCompat.getColor(this, color))
    }

    private fun updateIntervalLabel(min: Int, max: Int) {
        binding.intervalLabelText.text = if (min == max)
            getString(R.string.interval_fixed_label, min)
        else
            getString(R.string.interval_range_label, min, max)
    }

    private fun highlightMode(mode: JiggleMode) {
        val id = when (mode) {
            JiggleMode.SILENT -> R.id.mode_btn_silent
            JiggleMode.HUMAN_LIKE -> R.id.mode_btn_human
            JiggleMode.ACTIVE_WORK -> R.id.mode_btn_active
        }
        binding.modeToggleGroup.check(id)
    }

    private fun updateRunningIndicator() {
        val svc = service ?: return
        val jiggling = svc.isJiggling
        binding.runningIndicatorText.text = when {
            !jiggling -> ""
            svc.scheduleEnabled && !svc.isWithinSchedule() -> getString(R.string.currently_paused_schedule)
            else -> getString(R.string.currently_running, localizedModeLabel(svc.jiggleMode))
        }
        animateDot(jiggling && svc.isConnected)
    }

    /** Subtle pulsing on the status dot while actively jiggling — otherwise it just sits solid. */
    private fun animateDot(pulsing: Boolean) {
        if (pulsing) {
            if (dotAnimator?.isRunning == true) return
            dotAnimator = ObjectAnimator.ofFloat(binding.statusDot, "alpha", 1f, 0.35f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            dotAnimator?.cancel()
            dotAnimator = null
            binding.statusDot.alpha = 1f
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
        dotAnimator?.cancel()
        service?.setStatusListener(null)
        if (::binding.isInitialized && service != null) unbindService(serviceConnection)
        super.onDestroy()
    }
}
