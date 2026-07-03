package com.example.jigglemouse

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

enum class KeyboardLayout { US_QWERTY, TURKISH_Q }


/**
 * A standalone HID keyboard implementation — intentionally NOT shared with
 * HidMouse/JiggleService. It registers its own BluetoothHidDevice app only
 * while keyboard mode is open, and unregisters on close. Since only one HID
 * app can be registered at a time, this and the mouse are always mutually
 * exclusive, which keeps keyboard mode from ever touching the mouse's
 * connection, identity, or jiggle state.
 */
class HidKeyboard(private val context: Context, private val listener: HidMouse.Listener) {

    private val descriptor: ByteArray = byteArrayOf(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x06,             // Usage (Keyboard)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x05, 0x07,             //   Usage Page (Key Codes)
        0x19, 0xE0.toByte(),    //   Usage Minimum (224)
        0x29, 0xE7.toByte(),    //   Usage Maximum (231)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95.toByte(), 0x08,    //   Report Count (8)
        0x81.toByte(), 0x02,    //   Input (Data, Var, Abs) - modifier byte
        0x95.toByte(), 0x01,    //   Report Count (1)
        0x75, 0x08,             //   Report Size (8)
        0x81.toByte(), 0x01,    //   Input (Const) - reserved byte
        0x95.toByte(), 0x06,    //   Report Count (6)
        0x75, 0x08,             //   Report Size (8)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x65,             //   Logical Maximum (101)
        0x05, 0x07,             //   Usage Page (Key Codes)
        0x19, 0x00,             //   Usage Minimum (0)
        0x29, 0x65,             //   Usage Maximum (101)
        0x81.toByte(), 0x00,    //   Input (Data, Array) - 6-key array
        0xC0.toByte()           // End Collection
    )

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    var isConnected = false
        private set
    var isRegistered = false
        private set

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "JiggleMouse Keyboard",
                "Wireless keyboard",
                "JiggleMouse",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
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
        val adapter = bm.adapter ?: run { listener.onError(context.getString(R.string.err_no_adapter)); return }
        try {
            adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            listener.onError(context.getString(R.string.err_missing_permission, e.message))
        }
    }

    fun connectTo(device: BluetoothDevice) {
        try {
            hidDevice?.connect(device)
        } catch (e: SecurityException) {
            listener.onError(context.getString(R.string.err_missing_permission, e.message))
        }
    }

    /** Sends one key press+release. [usageId] is a USB HID keyboard usage ID, [shift] applies Left-Shift. */
    fun sendKey(usageId: Int, shift: Boolean = false) {
        val d = connectedDevice ?: return
        val modifier = if (shift) 0x02 else 0x00 // left shift bit
        val down = byteArrayOf(modifier.toByte(), 0, usageId.toByte(), 0, 0, 0, 0, 0)
        val up = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        try {
            hidDevice?.sendReport(d, 0, down)
            hidDevice?.sendReport(d, 0, up)
        } catch (e: SecurityException) {
            listener.onError(context.getString(R.string.err_missing_permission, e.message))
        } catch (e: Exception) {
            Log.w("HidKeyboard", "sendReport failed", e)
        }
    }

    fun stop() {
        try {
            connectedDevice?.let { hidDevice?.disconnect(it) }
            hidDevice?.unregisterApp()
        } catch (e: SecurityException) {
            // ignore on teardown
        }
    }

    companion object {
        // USB HID usage IDs.
        const val KEY_ENTER = 0x28
        const val KEY_BACKSPACE = 0x2A
        const val KEY_SPACE = 0x2C

        /** Maps a printable character to (usageId, needsShift) for standard US QWERTY, or null if unsupported. */
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

        /**
         * Maps a character to (usageId, needsShift) for the Turkish-Q layout —
         * ONLY correct if the receiving computer's own OS keyboard layout is
         * also set to Turkish-Q. HID reports encode physical key position, not
         * characters; the receiving OS decides what a position means. Physical
         * positions below are taken directly from Microsoft's published
         * KBDTUQ.DLL scancode table (kbdlayout.info/kbdtuq/scancodes),
         * converted from Set-1 scancode to USB HID usage ID:
         *   ğ/Ğ → scancode 1A → usage 0x2F (US "[" position)
         *   ü/Ü → scancode 1B → usage 0x30 (US "]" position)
         *   ş/Ş → scancode 27 → usage 0x33 (US ";" position)
         *   i/İ → scancode 28 → usage 0x34 (US "'" position) — dotted i, distinct from dotless I
         *   ö/Ö → scancode 33 → usage 0x36 (US "," position)
         *   ç/Ç → scancode 34 → usage 0x37 (US "." position)
         *   .   → scancode 35 → usage 0x38 (US "/" position) — period moved here
         *   ,   → scancode 2B → usage 0x31 (US "\" position) — comma moved here
         *   ı/I → scancode 17 (same as letter I) → usage 0x0C — dotless i, same physical key as English I
         */
        fun turkishQCharToKey(c: Char): Pair<Int, Boolean>? {
            return when (c) {
                'ğ' -> 0x2F to false
                'Ğ' -> 0x2F to true
                'ü' -> 0x30 to false
                'Ü' -> 0x30 to true
                'ş' -> 0x33 to false
                'Ş' -> 0x33 to true
                'i' -> 0x34 to false            // dotted lowercase i
                'İ' -> 0x34 to true             // dotted uppercase İ (U+0130)
                'ö' -> 0x36 to false
                'Ö' -> 0x36 to true
                'ç' -> 0x37 to false
                'Ç' -> 0x37 to true
                '.' -> 0x38 to false
                '>' -> 0x38 to true
                ',' -> 0x31 to false
                '<' -> 0x31 to true
                'ı' -> 0x0C to false             // dotless lowercase ı (U+0131)
                'I' -> 0x0C to true              // dotless uppercase I (same glyph as ASCII I)
                in 'a'..'h' -> (0x04 + (c - 'a')) to false
                in 'j'..'z' -> (0x04 + (c - 'a')) to false // skips i/I, handled above
                in 'A'..'H' -> (0x04 + (c - 'A')) to true
                in 'J'..'Z' -> (0x04 + (c - 'A')) to true
                in '1'..'9' -> (0x1E + (c - '1')) to false
                '0' -> 0x27 to false
                ' ' -> KEY_SPACE to false
                '\n' -> KEY_ENTER to false
                else -> null // AltGr-level symbols (!,@,#, etc.) not supported — see README
            }
        }
    }
}
