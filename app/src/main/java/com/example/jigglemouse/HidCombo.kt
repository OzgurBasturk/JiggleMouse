package com.example.jigglemouse

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * A single Bluetooth HID app presenting BOTH a mouse and a keyboard via one
 * combined report descriptor (Report ID 1 = mouse, Report ID 2 = keyboard),
 * the same way a real wireless combo receiver (Logitech MK235, Microsoft
 * Wireless Combo 900, etc.) does. Registered exactly once for the lifetime
 * of the connection — switching between mouse/trackpad use and keyboard use
 * is just choosing which report ID to send, never a re-registration or a
 * new pairing. This avoids the "computer doesn't recognize the new HID
 * service" problem that plagues re-registering a different HID identity
 * mid-session.
 */
class HidCombo(
    private val context: Context,
    private val listener: Listener,
    private var sdpName: String = "JiggleMouse Combo"
) {
    interface Listener {
        fun onRegistered()
        fun onUnregistered()
        fun onConnectionState(device: BluetoothDevice?, connected: Boolean)
        fun onError(message: String)
    }

    private val descriptor: ByteArray = byteArrayOf(
        // ---- Mouse: Report ID 1 (buttons, dx, dy, wheel) ----
        0x05, 0x01,
        0x09.toByte(), 0x02,
        0xA1.toByte(), 0x01,
        0x85.toByte(), 0x01,             // Report ID (1)
        0x09, 0x01,
        0xA1.toByte(), 0x00,
        0x05, 0x09,
        0x19, 0x01,
        0x29, 0x03,
        0x15, 0x00,
        0x25, 0x01,
        0x95.toByte(), 0x03,
        0x75, 0x01,
        0x81.toByte(), 0x02,
        0x95.toByte(), 0x01,
        0x75, 0x05,
        0x81.toByte(), 0x03,
        0x05, 0x01,
        0x09, 0x30,
        0x09, 0x31,
        0x15, 0x81.toByte(),
        0x25, 0x7F,
        0x75, 0x08,
        0x95.toByte(), 0x02,
        0x81.toByte(), 0x06,
        0x09, 0x38,
        0x15, 0x81.toByte(),
        0x25, 0x7F,
        0x75, 0x08,
        0x95.toByte(), 0x01,
        0x81.toByte(), 0x06,
        0xC0.toByte(),
        0xC0.toByte(),
        // ---- Keyboard: Report ID 2 (modifier, reserved, 6-key array) ----
        0x05, 0x01,
        0x09, 0x06,
        0xA1.toByte(), 0x01,
        0x85.toByte(), 0x02,             // Report ID (2)
        0x05, 0x07,
        0x19, 0xE0.toByte(),
        0x29, 0xE7.toByte(),
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95.toByte(), 0x08,
        0x81.toByte(), 0x02,
        0x95.toByte(), 0x01,
        0x75, 0x08,
        0x81.toByte(), 0x01,
        0x95.toByte(), 0x06,
        0x75, 0x08,
        0x15, 0x00,
        0x25, 0x65,
        0x05, 0x07,
        0x19, 0x00,
        0x29, 0x65,
        0x81.toByte(), 0x00,
        0xC0.toByte()
    )

    private var hidDevice: BluetoothHidDevice? = null
    private var adapterRef: BluetoothAdapter? = null
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
                "Wireless combo",
                sdpName,
                BluetoothHidDevice.SUBCLASS1_COMBO,
                descriptor
            )
            try {
                hidDevice?.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)
            } catch (e: SecurityException) {
                listener.onError(context.getString(R.string.err_missing_permission, e.message))
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
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

    fun start() {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        adapterRef = adapter
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

    fun connectTo(device: BluetoothDevice) {
        val hd = hidDevice
        if (hd == null || !isRegistered) {
            listener.onError(context.getString(R.string.err_not_ready))
            return
        }
        try {
            if (!hd.connect(device)) listener.onError(context.getString(R.string.err_connect_rejected))
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

    // --- Mouse (Report ID 1) ---

    fun move(dx: Int, dy: Int) = moveWithButtons(dx, dy, left = false, right = false)

    fun moveWithButtons(dx: Int, dy: Int, left: Boolean, right: Boolean, middle: Boolean = false, wheel: Int = 0) {
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
        sendReport(REPORT_ID_MOUSE, report)
    }

    fun scroll(amount: Int) = moveWithButtons(0, 0, left = false, right = false, wheel = amount)
    fun pressButton(left: Boolean = false, right: Boolean = false) = moveWithButtons(0, 0, left, right)
    fun releaseButtons() = moveWithButtons(0, 0, left = false, right = false)
    fun click(left: Boolean = false, right: Boolean = false) {
        pressButton(left, right)
        releaseButtons()
    }

    // --- Keyboard (Report ID 2) ---

    fun sendKey(usageId: Int, shift: Boolean = false) {
        val modifier = if (shift) 0x02 else 0x00
        sendReport(REPORT_ID_KEYBOARD, byteArrayOf(modifier.toByte(), 0, usageId.toByte(), 0, 0, 0, 0, 0))
        sendReport(REPORT_ID_KEYBOARD, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
    }

    private fun sendReport(id: Int, data: ByteArray) {
        val d = connectedDevice ?: return
        try {
            hidDevice?.sendReport(d, id, data)
        } catch (e: SecurityException) {
            listener.onError(context.getString(R.string.err_missing_permission, e.message))
        } catch (e: Exception) {
            Log.w("HidCombo", "sendReport failed", e)
        }
    }

    fun stop() {
        try {
            connectedDevice?.let { hidDevice?.disconnect(it) }
            hidDevice?.unregisterApp()
            hidDevice?.let { adapterRef?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (e: SecurityException) {
            // ignore on teardown
        }
        hidDevice = null
        isRegistered = false
        isConnected = false
    }

    companion object {
        private const val REPORT_ID_MOUSE = 1
        private const val REPORT_ID_KEYBOARD = 2

        const val KEY_ENTER = 0x28
        const val KEY_BACKSPACE = 0x2A
        const val KEY_SPACE = 0x2C

        fun usQwertyCharToKey(c: Char): Pair<Int, Boolean>? {
            return when {
                c in 'a'..'z' -> (0x04 + (c - 'a')) to false
                c in 'A'..'Z' -> (0x04 + (c - 'A')) to true
                c in '1'..'9' -> (0x1E + (c - '1')) to false
                c == '0' -> 0x27 to false
                c == ' ' -> KEY_SPACE to false
                c == '\n' -> KEY_ENTER to false
                c == '-' -> 0x2D to false
                c == '_' -> 0x2D to true
                c == '=' -> 0x2E to false
                c == '+' -> 0x2E to true
                c == '[' -> 0x2F to false
                c == '{' -> 0x2F to true
                c == ']' -> 0x30 to false
                c == '}' -> 0x30 to true
                c == '\\' -> 0x31 to false
                c == '|' -> 0x31 to true
                c == ';' -> 0x33 to false
                c == ':' -> 0x33 to true
                c == '\'' -> 0x34 to false
                c == '"' -> 0x34 to true
                c == ',' -> 0x36 to false
                c == '<' -> 0x36 to true
                c == '.' -> 0x37 to false
                c == '>' -> 0x37 to true
                c == '/' -> 0x38 to false
                c == '?' -> 0x38 to true
                c == '`' -> 0x35 to false
                c == '~' -> 0x35 to true
                c == '!' -> 0x1E to true
                c == '@' -> 0x1F to true
                c == '#' -> 0x20 to true
                c == '$' -> 0x21 to true
                c == '%' -> 0x22 to true
                c == '^' -> 0x23 to true
                c == '&' -> 0x24 to true
                c == '*' -> 0x25 to true
                c == '(' -> 0x26 to true
                c == ')' -> 0x27 to true
                else -> null
            }
        }

        /** See HidCombo docs / README for the source of these Turkish-Q physical positions. */
        fun turkishQCharToKey(c: Char): Pair<Int, Boolean>? {
            return when (c) {
                'ğ' -> 0x2F to false
                'Ğ' -> 0x2F to true
                'ü' -> 0x30 to false
                'Ü' -> 0x30 to true
                'ş' -> 0x33 to false
                'Ş' -> 0x33 to true
                'i' -> 0x34 to false
                'İ' -> 0x34 to true
                'ö' -> 0x36 to false
                'Ö' -> 0x36 to true
                'ç' -> 0x37 to false
                'Ç' -> 0x37 to true
                '.' -> 0x38 to false
                '>' -> 0x38 to true
                ',' -> 0x31 to false
                '<' -> 0x31 to true
                'ı' -> 0x0C to false
                'I' -> 0x0C to true
                in 'a'..'h' -> (0x04 + (c - 'a')) to false
                in 'j'..'z' -> (0x04 + (c - 'a')) to false
                in 'A'..'H' -> (0x04 + (c - 'A')) to true
                in 'J'..'Z' -> (0x04 + (c - 'A')) to true
                in '1'..'9' -> (0x1E + (c - '1')) to false
                '0' -> 0x27 to false
                ' ' -> KEY_SPACE to false
                '\n' -> KEY_ENTER to false
                else -> null
            }
        }
    }
}
