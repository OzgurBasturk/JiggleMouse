package com.example.jigglemouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.random.Random

enum class JiggleMode { SILENT, HUMAN_LIKE, ACTIVE_WORK }

data class DeviceProfile(val name: String, val address: String)

/**
 * Owns the BluetoothHidDevice connection and the jiggle timer. Runs as a
 * foreground service (with a persistent notification, as Android requires)
 * so jiggling keeps working with the screen off or the app backgrounded.
 * Also owns settings persistence, auto-reconnect, saved device profiles,
 * and the optional jiggle schedule window.
 */
class JiggleService : Service(), HidMouse.Listener {

    interface StatusListener {
        fun onRegistered() {}
        fun onUnregistered() {}
        fun onConnectionState(deviceName: String, connected: Boolean) {}
        fun onError(message: String) {}
        fun onJiggled() {}
        fun onReconnecting(attempt: Int) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): JiggleService = this@JiggleService
    }

    private val binder = LocalBinder()
    private var statusListener: StatusListener? = null

    lateinit var hidMouse: HidMouse
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var jiggleRunning = false
    var jiggleMode: JiggleMode = JiggleMode.SILENT
        private set

    private var wakeLock: PowerManager.WakeLock? = null

    private var minIntervalMs = 30_000L
    private var maxIntervalMs = 90_000L

    private val prefs by lazy { getSharedPreferences("jigglemouse_prefs", MODE_PRIVATE) }

    val isConnected: Boolean get() = ::hidMouse.isInitialized && hidMouse.isConnected
    val isRegistered: Boolean get() = ::hidMouse.isInitialized && hidMouse.isRegistered
    val isJiggling: Boolean get() = jiggleRunning

    // --- Lifecycle ---------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        restoreSettings()
        hidMouse = HidMouse(applicationContext, this)
        hidMouse.start()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_ready)))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setStatusListener(listener: StatusListener?) {
        statusListener = listener
    }

    // --- Settings persistence ----------------------------------------

    private fun restoreSettings() {
        jiggleMode = try {
            JiggleMode.valueOf(prefs.getString(KEY_MODE, JiggleMode.SILENT.name)!!)
        } catch (e: IllegalArgumentException) { JiggleMode.SILENT }
        minIntervalMs = prefs.getLong(KEY_MIN_MS, 30_000L)
        maxIntervalMs = prefs.getLong(KEY_MAX_MS, 90_000L)
        scheduleEnabled = prefs.getBoolean(KEY_SCHED_ENABLED, false)
        scheduleStartMin = prefs.getInt(KEY_SCHED_START, 9 * 60)
        scheduleEndMin = prefs.getInt(KEY_SCHED_END, 18 * 60)
        scheduleWeekdaysOnly = prefs.getBoolean(KEY_SCHED_WEEKDAYS, false)
        lastDeviceAddress = prefs.getString(KEY_LAST_DEVICE, null)
    }

    // --- Connection ----------------------------------------------------

    private var lastDeviceAddress: String? = null
    private var manualDisconnect = false
    private var reconnectAttempt = 0
    private var autoReconnectEnabled = true

    fun connectTo(device: BluetoothDevice) {
        manualDisconnect = false
        lastDeviceAddress = device.address
        prefs.edit().putString(KEY_LAST_DEVICE, device.address).apply()
        hidMouse.connectTo(device)
    }

    fun disconnect() {
        manualDisconnect = true
        handler.removeCallbacks(reconnectRunnable)
        stopJiggle()
        hidMouse.disconnect()
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        autoReconnectEnabled = enabled
        if (!enabled) handler.removeCallbacks(reconnectRunnable)
    }

    val isAutoReconnectEnabled: Boolean get() = autoReconnectEnabled

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            val address = lastDeviceAddress ?: return
            if (isConnected || manualDisconnect || !autoReconnectEnabled) return
            try {
                val bm = getSystemService(BluetoothManager::class.java)
                val device = bm.adapter?.getRemoteDevice(address) ?: return
                reconnectAttempt++
                statusListener?.onReconnecting(reconnectAttempt)
                hidMouse.connectTo(device)
            } catch (e: Exception) {
                // ignore, will retry
            }
            val delay = (5_000L + reconnectAttempt * 5_000L).coerceAtMost(60_000L)
            handler.postDelayed(this, delay)
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnectEnabled || manualDisconnect || lastDeviceAddress == null) return
        reconnectAttempt = 0
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, 5_000L)
    }

    // --- Device profiles (saved named connections) ----------------------

    fun saveProfile(name: String, address: String) {
        val list = listProfiles().filter { it.address != address }.toMutableList()
        list.add(DeviceProfile(name, address))
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply { put("name", p.name); put("address", p.address) })
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun removeProfile(address: String) {
        val list = listProfiles().filter { it.address != address }
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply { put("name", p.name); put("address", p.address) })
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun listProfiles(): List<DeviceProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                DeviceProfile(o.getString("name"), o.getString("address"))
            }
        } catch (e: Exception) { emptyList() }
    }

    // --- Jiggle mode / interval (persisted) ------------------------------

    fun setJiggleMode(mode: JiggleMode) {
        jiggleMode = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    /** Sets the randomized min/max wait between jiggles, in seconds. min may equal max
     *  for a fixed interval; only constraint is min <= max (auto-swapped if reversed). */
    fun setIntervalSeconds(minSec: Int, maxSec: Int) {
        val a = minSec.coerceAtLeast(1)
        val b = maxSec.coerceAtLeast(1)
        minIntervalMs = minOf(a, b) * 1000L
        maxIntervalMs = maxOf(a, b) * 1000L
        prefs.edit().putLong(KEY_MIN_MS, minIntervalMs).putLong(KEY_MAX_MS, maxIntervalMs).apply()
    }

    val minIntervalSeconds: Int get() = (minIntervalMs / 1000L).toInt()
    val maxIntervalSeconds: Int get() = (maxIntervalMs / 1000L).toInt()

    fun setJiggleEnabled(enabled: Boolean) {
        if (enabled) startJiggle() else stopJiggle()
    }

    // --- Schedule (persisted) --------------------------------------------

    var scheduleEnabled: Boolean = false
        private set
    var scheduleStartMin: Int = 9 * 60
        private set
    var scheduleEndMin: Int = 18 * 60
        private set
    var scheduleWeekdaysOnly: Boolean = false
        private set

    fun setSchedule(enabled: Boolean, startMin: Int, endMin: Int, weekdaysOnly: Boolean) {
        scheduleEnabled = enabled
        scheduleStartMin = startMin.coerceIn(0, 23 * 60 + 59)
        scheduleEndMin = endMin.coerceIn(0, 23 * 60 + 59)
        scheduleWeekdaysOnly = weekdaysOnly
        prefs.edit()
            .putBoolean(KEY_SCHED_ENABLED, enabled)
            .putInt(KEY_SCHED_START, scheduleStartMin)
            .putInt(KEY_SCHED_END, scheduleEndMin)
            .putBoolean(KEY_SCHED_WEEKDAYS, weekdaysOnly)
            .apply()
    }

    /** True if jiggling should be actively happening right now, given the schedule. */
    fun isWithinSchedule(): Boolean {
        if (!scheduleEnabled) return true
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dow = cal.get(Calendar.DAY_OF_WEEK) // Sunday=1 .. Saturday=7
        if (scheduleWeekdaysOnly && (dow == Calendar.SUNDAY || dow == Calendar.SATURDAY)) return false
        return if (scheduleStartMin <= scheduleEndMin) {
            nowMin in scheduleStartMin..scheduleEndMin
        } else {
            nowMin >= scheduleStartMin || nowMin <= scheduleEndMin
        }
    }

    // --- Keyboard-mode isolation ------------------------------------

    fun releaseForKeyboardMode() {
        handler.removeCallbacks(reconnectRunnable)
        stopJiggle()
        if (::hidMouse.isInitialized) hidMouse.stop()
        updateNotification(getString(R.string.notif_paused_keyboard))
    }

    fun reclaimMouseMode() {
        hidMouse = HidMouse(applicationContext, this)
        hidMouse.start()
        updateNotification(getString(R.string.notif_ready))
    }

    // --- Bluetooth name spoofing -----------------------------------------

    fun spoofDeviceName(name: String) {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            val adapter = bm.adapter ?: return
            if (!prefs.contains(KEY_ORIGINAL_NAME)) {
                prefs.edit().putString(KEY_ORIGINAL_NAME, adapter.name ?: "").apply()
            }
            adapter.setName(name)
        } catch (e: SecurityException) {
            statusListener?.onError(getString(R.string.missing_name_permission))
        }
    }

    fun restoreDeviceName() {
        val original = prefs.getString(KEY_ORIGINAL_NAME, null) ?: return
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            bm.adapter?.setName(original)
            prefs.edit().remove(KEY_ORIGINAL_NAME).apply()
        } catch (e: SecurityException) {
            // ignore — nothing more we can do
        }
    }

    val currentAdapterName: String?
        get() = try {
            (getSystemService(BluetoothManager::class.java)).adapter?.name
        } catch (e: SecurityException) { null }

    // --- Jiggle loop -------------------------------------------------

    private fun localizedModeName(): String = when (jiggleMode) {
        JiggleMode.SILENT -> getString(R.string.mode_name_silent)
        JiggleMode.HUMAN_LIKE -> getString(R.string.mode_name_human)
        JiggleMode.ACTIVE_WORK -> getString(R.string.mode_name_active)
    }

    private val jiggleTick = object : Runnable {
        override fun run() {
            if (!jiggleRunning) return
            if (hidMouse.isConnected && isWithinSchedule()) {
                when (jiggleMode) {
                    JiggleMode.SILENT -> silentJiggle()
                    JiggleMode.HUMAN_LIKE -> humanLikeJiggle()
                    JiggleMode.ACTIVE_WORK -> activeWorkJiggle()
                }
                statusListener?.onJiggled()
                updateNotification(getString(R.string.notif_jiggling, localizedModeName()))
            } else if (hidMouse.isConnected && scheduleEnabled) {
                updateNotification(getString(R.string.notif_outside_schedule))
            }
            val nextDelayMs = if (scheduleEnabled && !isWithinSchedule()) {
                60_000L
            } else {
                Random.nextLong(minIntervalMs, maxIntervalMs + 1)
            }
            handler.postDelayed(this, nextDelayMs)
        }
    }

    private fun silentJiggle() {
        hidMouse.move(2, 0)
        handler.postDelayed({ hidMouse.move(-2, 0) }, 80)
    }

    private fun activeWorkJiggle() {
        val legs = Random.nextInt(3, 7)
        sendWorkLeg(legs, 1)
    }

    private fun sendWorkLeg(totalLegs: Int, legNumber: Int) {
        if (legNumber > totalLegs) return
        val isBigJump = Random.nextInt(100) < 20
        val distance = if (isBigJump) Random.nextInt(120, 260) else Random.nextInt(40, 130)
        val verticalBias = Random.nextDouble(0.55, 0.9)
        val goingDown = Random.nextBoolean()
        val goingRight = Random.nextBoolean()

        val dyTotal = (distance * verticalBias).toInt().let { if (goingDown) it else -it }
        val dxTotal = (distance * (1 - verticalBias)).toInt().let { if (goingRight) it else -it }
        val steps = Random.nextInt(14, 26)

        val overshoot = Random.nextInt(100) < 25
        if (overshoot) {
            val overshootFactor = 1.15
            sendEasedPath((dxTotal * overshootFactor).toInt(), (dyTotal * overshootFactor).toInt(), steps) {
                handler.postDelayed({
                    val correctDx = -(dxTotal * (overshootFactor - 1)).toInt()
                    val correctDy = -(dyTotal * (overshootFactor - 1)).toInt()
                    sendEasedPath(correctDx, correctDy, 4) {
                        scheduleNextLeg(totalLegs, legNumber)
                    }
                }, Random.nextLong(60, 150))
            }
        } else {
            sendEasedPath(dxTotal, dyTotal, steps) {
                scheduleNextLeg(totalLegs, legNumber)
            }
        }
    }

    private fun scheduleNextLeg(totalLegs: Int, legNumber: Int) {
        val longPause = Random.nextInt(100) < 20
        val pause = if (longPause) Random.nextLong(1200, 4000) else Random.nextLong(150, 700)
        handler.postDelayed({ sendWorkLeg(totalLegs, legNumber + 1) }, pause)
    }

    private fun humanLikeJiggle() {
        val angle = Random.nextDouble(0.0, 2 * Math.PI)
        val distance = Random.nextInt(6, 18)
        val steps = Random.nextInt(6, 12)
        val dxTotal = (Math.cos(angle) * distance).toInt()
        val dyTotal = (Math.sin(angle) * distance).toInt()

        sendEasedPath(dxTotal, dyTotal, steps) {
            handler.postDelayed({
                sendEasedPath(-dxTotal, -dyTotal, steps)
            }, Random.nextLong(200, 500))
        }
    }

    private fun sendEasedPath(totalDx: Int, totalDy: Int, steps: Int, onDone: (() -> Unit)? = null) {
        var sentX = 0
        var sentY = 0
        for (i in 1..steps) {
            val progress = i.toDouble() / steps
            val eased = 1 - Math.pow(1 - progress, 2.0)
            val targetX = (totalDx * eased).toInt()
            val targetY = (totalDy * eased).toInt()
            val stepDx = targetX - sentX
            val stepDy = targetY - sentY
            sentX = targetX
            sentY = targetY
            val delay = (i * Random.nextLong(12, 28))
            handler.postDelayed({
                if (stepDx != 0 || stepDy != 0) hidMouse.move(stepDx, stepDy)
                if (i == steps) onDone?.invoke()
            }, delay)
        }
    }

    private fun startJiggle() {
        if (jiggleRunning) return
        jiggleRunning = true
        acquireWakeLock()
        handler.post(jiggleTick)
        updateNotification(getString(R.string.notif_jiggling, localizedModeName()))
    }

    private fun stopJiggle() {
        jiggleRunning = false
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        updateNotification(getString(if (isConnected) R.string.notif_paused else R.string.notif_disconnected))
    }

    // --- Wake lock ---------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JiggleMouse::JiggleWakeLock")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    // --- HidMouse.Listener --------------------------------------------

    override fun onRegistered() {
        statusListener?.onRegistered()
    }

    override fun onUnregistered() {
        statusListener?.onUnregistered()
    }

    override fun onConnectionState(device: BluetoothDevice?, connected: Boolean) {
        val name = try { device?.name ?: device?.address ?: "device" } catch (e: SecurityException) { "device" }
        statusListener?.onConnectionState(name, connected)
        updateNotification(
            if (connected) getString(R.string.notif_connected_to, name) else getString(R.string.notif_disconnected)
        )
        if (connected) {
            handler.removeCallbacks(reconnectRunnable)
            reconnectAttempt = 0
        } else {
            stopJiggle()
            if (!manualDisconnect) scheduleReconnect()
        }
    }

    override fun onError(message: String) {
        statusListener?.onError(message)
    }

    // --- Notification ---------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopJiggle()
        handler.removeCallbacksAndMessages(null)
        if (::hidMouse.isInitialized) hidMouse.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jiggle_status"
        private const val NOTIFICATION_ID = 1
        private const val KEY_ORIGINAL_NAME = "original_bt_name"
        private const val KEY_MODE = "jiggle_mode"
        private const val KEY_MIN_MS = "min_interval_ms"
        private const val KEY_MAX_MS = "max_interval_ms"
        private const val KEY_LAST_DEVICE = "last_device_address"
        private const val KEY_PROFILES = "device_profiles"
        private const val KEY_SCHED_ENABLED = "schedule_enabled"
        private const val KEY_SCHED_START = "schedule_start_min"
        private const val KEY_SCHED_END = "schedule_end_min"
        private const val KEY_SCHED_WEEKDAYS = "schedule_weekdays_only"
    }
}
