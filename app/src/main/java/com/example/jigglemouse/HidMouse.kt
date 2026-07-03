package com.example.jigglemouse

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Thin wrapper around Android's BluetoothHidDevice API that registers the
 * phone as a standard 3-byte relative-mode HID mouse (buttons, dX, dY) and
 * lets you send move reports to whatever host device is connected.
 *
 * This is the same OS-level API (available since Android 9 / API 28) used
 * by any legitimate "phone as remote mouse/keyboard" app - no hidden APIs,
 * no root.
 */
class HidMouse(
    private val context: Context,
    private val listener: Listener,
    private var sdpName: String = "JiggleMouse"
) {

    interface Listener {
        fun onRegistered()
        fun onUnregistered()
        fun onConnectionState(device: BluetoothDevice?, connected: Boolean)
        fun onError(message: String)
    }

    // Standard USB HID mouse report descriptor: 1 byte of buttons (3 used +
    // 5 padding bits), then 1 signed byte each for relative X/Y, then 1
    // signed byte for the scroll wheel. 4-byte report total.
    private val descriptor: ByteArray = byteArrayOf(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02,    // Usage (Mouse)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x09, 0x01,             //   Usage (Pointer)
        0xA1.toByte(), 0x00,    //   Collection (Physical)
        0x05, 0x09,             //     Usage Page (Buttons)
        0x19, 0x01,             //     Usage Minimum (1)
        0x29, 0x03,             //     Usage Maximum (3)
        0x15, 0x00,             //     Logical Minimum (0)
        0x25, 0x01,             //     Logical Maximum (1)
        0x95.toByte(), 0x03,    //     Report Count (3)
        0x75, 0x01,             //     Report Size (1)
        0x81.toByte(), 0x02,    //     Input (Data, Var, Abs)
        0x95.toByte(), 0x01,    //     Report Count (1)
        0x75, 0x05,             //     Report Size (5)
        0x81.toByte(), 0x03,    //     Input (Const) - padding
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30,             //     Usage (X)
        0x09, 0x31,             //     Usage (Y)
        0x15, 0x81.toByte(),    //     Logical Minimum (-127)
        0x25, 0x7F,             //     Logical Maximum (127)
        0x75, 0x08,             //     Report Size (8)
        0x95.toByte(), 0x02,    //     Report Count (2)
        0x81.toByte(), 0x06,    //     Input (Data, Var, Rel)
        0x09, 0x38,             //     Usage (Wheel)
        0x15, 0x81.toByte(),    //     Logical Minimum (-127)
        0x25, 0x7F,             //     Logical Maximum (127)
        0x75, 0x08,             //     Report Size (8)
        0x95.toByte(), 0x01,    //     Report Count (1)
        0x81.toByte(), 0x06,    //     Input (Data, Var, Rel)
        0xC0.toByte(),          //   End Collection
        0xC0.toByte()           // End Collection
    )

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    var isConnected: Boolean = false
        private set
    var isRegistered: Boolean = false
        private set

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            val sdp = BluetoothHidDeviceAppSdpSettings(
                sdpName,
                "Wireless mouse",
                sdpName,
                BluetoothHidDevice.SUBCLASS1_MOUSE,
                descriptor
            )
            try {
                hidDevice?.registerApp(
                    sdp, null, null,
                    Executors.newSingleThreadExecutor(),
                    hidCallback
                )
            } catch (e: SecurityException) {
                listener.onError(context.getString(R.string.err_missing_permission, e.message))
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            if (registered) listener.onRegistered() else listener.onUnregistered()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val connected = state == BluetoothProfile.STATE_CONNECTED
            isConnected = connected
            connectedDevice = if (connected) device else null
            listener.onConnectionState(device, connected)
        }
    }

    /** Call once, e.g. from Activity.onCreate, to register the HID app. */
    fun start() {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        if (adapter == null) {
            listener.onError(context.getString(R.string.err_no_adapter))
            return
        }
        try {
            adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            listener.onError(context.getString(R.string.err_missing_permission, e.message))
        }
    }

    /** Ask a previously-paired device to connect as our HID host. */
    fun connectTo(device: BluetoothDevice) {
        val hd = hidDevice
        if (hd == null || !isRegistered) {
            listener.onError(context.getString(R.string.err_not_ready))
            return
        }
        try {
            val started = hd.connect(device)
            if (!started) {
                listener.onError(context.getString(R.string.err_connect_rejected))
            }
        } catch (e: SecurityException) {
            listener.onError(context.getString(R.string.err_missing_permission, e.message))
        }
    }

    fun disconnect() {
        val d = connectedDevice ?: return
        try {
            hidDevice?.disconnect(d)
        } catch (e: SecurityException) {
            listener.onError(context.getString(R.string.err_missing_permission, e.message))
        }
    }

    /** Sends a small relative move; call twice (out and back) to "jiggle". */
    fun move(dx: Int, dy: Int) = moveWithButtons(dx, dy, left = false, right = false)

    /** Relative move that also reports current button state — used by the trackpad for drag. */
    fun moveWithButtons(dx: Int, dy: Int, left: Boolean, right: Boolean, middle: Boolean = false, wheel: Int = 0) {
        val d = connectedDevice ?: return
        var buttons = 0
        if (left) buttons = buttons or 0x01
        if (right) buttons = buttons or 0x02
        if (middle) buttons = buttons or 0x04
        val report = byteArrayOf(
            buttons.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte()
        )
        try {
            hidDevice?.sendReport(d, 0, report)
        } catch (e: SecurityException) {
            listener.onError(context.getString(R.string.err_missing_permission, e.message))
        } catch (e: Exception) {
            Log.w("HidMouse", "sendReport failed", e)
        }
    }

    /** Sends a scroll-wheel tick; positive scrolls up, negative scrolls down (host-dependent). */
    fun scroll(amount: Int) = moveWithButtons(0, 0, left = false, right = false, wheel = amount)

    /** Explicit press/release pair — callers (e.g. trackpad UI) should add a
     *  short delay between them so the host reliably registers a click. */
    fun pressButton(left: Boolean = false, right: Boolean = false) =
        moveWithButtons(0, 0, left, right)

    fun releaseButtons() = moveWithButtons(0, 0, left = false, right = false)

    /** Convenience for a synchronous tap; prefer pressButton()/releaseButtons() with a delay for real clicks. */
    fun click(left: Boolean = false, right: Boolean = false) {
        pressButton(left, right)
        releaseButtons()
    }

    fun stop() {
        try {
            connectedDevice?.let { hidDevice?.disconnect(it) }
            hidDevice?.unregisterApp()
        } catch (e: SecurityException) {
            // ignore on teardown
        }
    }
}
