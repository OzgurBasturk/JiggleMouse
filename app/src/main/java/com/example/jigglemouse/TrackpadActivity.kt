package com.example.jigglemouse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.example.jigglemouse.databinding.ActivityTrackpadBinding
import kotlin.math.roundToInt

/**
 * Turns the app into a real trackpad: drag on the surface to move the
 * cursor, tap the buttons to left/right click. Uses the same connected
 * HidMouse instance the JiggleService already owns — no separate connection.
 */
class TrackpadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrackpadBinding
    private var service: JiggleService? = null
    private val handler = Handler(Looper.getMainLooper())

    private var lastX = 0f
    private var lastY = 0f

    // Sensitivity: how many screen px of finger drag = 1 HID move unit.
    private val sensitivityDivisor = 3f
    private val scrollDivisor = 25f

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
        binding = ActivityTrackpadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.trackpadSurface.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
        binding.btnLeftClick.setOnClickListener { performClick(left = true) }
        binding.btnRightClick.setOnClickListener { performClick(left = false) }

        bindService(Intent(this, JiggleService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateStatus() {
        val connected = service?.isConnected == true
        binding.trackpadStatus.text = getString(
            if (connected) R.string.trackpad_ready else R.string.trackpad_not_connected
        )
        binding.trackpadSurface.isEnabled = connected
    }

    private fun handleTouch(event: MotionEvent) {
        val hidMouse = service?.hidMouse ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = averageX(event)
                lastY = averageY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val curX = averageX(event)
                val curY = averageY(event)
                val dyRaw = curY - lastY
                if (event.pointerCount >= 2) {
                    // Two fingers: scroll instead of moving the cursor.
                    val ticks = (-dyRaw / scrollDivisor).roundToInt()
                    if (ticks != 0) {
                        hidMouse.scroll(ticks)
                        lastY = curY
                    }
                    lastX = curX
                } else {
                    val dx = ((curX - lastX) / sensitivityDivisor).roundToInt()
                    val dy = (dyRaw / sensitivityDivisor).roundToInt()
                    if (dx != 0 || dy != 0) {
                        hidMouse.move(dx, dy)
                        lastX = curX
                        lastY = curY
                    }
                }
            }
        }
    }

    private fun averageX(event: MotionEvent): Float =
        (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()

    private fun averageY(event: MotionEvent): Float =
        (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()

    private fun performClick(left: Boolean) {
        val hidMouse = service?.hidMouse ?: return
        hidMouse.pressButton(left = left, right = !left)
        handler.postDelayed({ hidMouse.releaseButtons() }, 60)
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
