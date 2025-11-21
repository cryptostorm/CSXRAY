package com.cryptostorm.xray

import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

const val ACTION_START = "com.cryptostorm.xray.ACTION_START"
const val ACTION_STOP  = "com.cryptostorm.xray.ACTION_STOP"

class CSXrayService : Service() {
    private val chanId = "xray_tunnel"
    private val svcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var startJob: Job? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    /**
    * Write a one-line bootstrap error to error.log. This is used when Xray
    * fails before it can honor the "log" section (e.g., malformed config).
    */
    private fun appendBootstrapError(msg: String) {
        runCatching {
            val f = File(filesDir, "error.log")
            // Ensure directory exists; filesDir always exists but be defensive.
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            f.appendText("[bootstrap] $msg\n")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Notification channels (O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                chanId,
                "Xray tunnel",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel) // idempotent if it already exists
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACTION_STOP -> {
                started.set(false)
                startJob?.cancel()
                svcScope.launch {
                    runCatching { XrayBridge.stopXray() }
                    XraySignals.notifyStopped()
                    try { wifiLock?.release() } catch (_: Throwable) {}
                    try { cpuLock?.release() } catch (_: Throwable) {}
                    wifiLock = null; cpuLock = null
                    // Stop foreground compat
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                // If already started, ignore duplicate
                if (!started.compareAndSet(false, true)) return START_STICKY

                val n = NotificationCompat.Builder(this, chanId)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle("Xray active")
                    .setContentText("Forwarding WireGuard via REALITY")
                    .setOngoing(true)
                    .build()
                startForeground(1, n)

                // Unbind from VPN
                try {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cm.bindProcessToNetwork(null)
                    } else {
                        @Suppress("DEPRECATION")
                        ConnectivityManager.setProcessDefaultNetwork(null)
                    }
                } catch (_: Exception) {}

                // Optional: performance locks (release on STOP)
                try {
                    val wm =
                        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiLock =
                        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CSXRAY:WifiLock")
                            .apply { setReferenceCounted(false); acquire() }
                } catch (_: Throwable) { wifiLock = null }

                try {
                    val pm =
                        applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CSXRAY:CpuLock")
                        .apply { setReferenceCounted(false); acquire() }
                } catch (_: Throwable) { cpuLock = null }


                startJob = svcScope.launch {
                    val cfgFile = File(filesDir, "config.json")
                    val cfgPath = cfgFile.absolutePath

                    if (!cfgFile.exists()) {
                        appendBootstrapError("config.json not found at $cfgPath")
                    }

                    val rc: Int = try {
                        XrayBridge.StartXray(cfgPath)
                    } catch (t: Throwable) {
                        appendBootstrapError("StartXray exception: ${t.message ?: t::class.java.simpleName}")
                        -1
                    }

                    if (rc == 0 && started.get()) {
                        // Xray is up; remain in LISTENING until real WG traffic is observed.
                        XraySignals.notifyListening()
                    } else {
                        // Failed start -> record a visible reason and clean up.
                        if (rc != 0) {
                            appendBootstrapError("Xray failed to start (rc=$rc). Check config JSON or custom config.")
                        }
                        started.set(false)
                        XraySignals.notifyStopped()
                        try { wifiLock?.release() } catch (_: Throwable) {}
                        try { cpuLock?.release() } catch (_: Throwable) {}
                        wifiLock = null; cpuLock = null
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(Service.STOP_FOREGROUND_REMOVE)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(true)
                        }
                        stopSelf()
                    }
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    /**
    * Called when the user swipes the task from Recents.
    * We only tear down if the tunnel isn't running, so the process can exit cleanly.
    * If the tunnel IS running, we keep the foreground service alive
    */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!started.get()) {
            svcScope.launch {
                runCatching { XrayBridge.stopXray() }
                XraySignals.notifyStopped()
                try { wifiLock?.release() } catch (_: Throwable) {}
                try { cpuLock?.release() } catch (_: Throwable) {}
                wifiLock = null
                cpuLock = null
                // Proper API-checked stopForeground:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
    }


    override fun onDestroy() {
        // Defensive: ensure everything is down if system kills us
        started.set(false)
        startJob?.cancel()
        svcScope.launch { runCatching { XrayBridge.stopXray() } }
        try { wifiLock?.release() } catch (_: Throwable) {}
        try { cpuLock?.release() } catch (_: Throwable) {}
        wifiLock = null; cpuLock = null
        svcScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}