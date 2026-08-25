package com.vpnbox.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vpnbox.MainActivity
import com.vpnbox.R
import com.vpnbox.data.model.ServerConfig
import com.vpnbox.data.model.Protocol
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class VpnTunnelService : VpnService() {

    companion object {
        private const val TAG = "VpnTunnelService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.vpnbox.CONNECT"
        const val ACTION_DISCONNECT = "com.vpnbox.DISCONNECT"

        private var instance: VpnTunnelService? = null
        fun getInstance(): VpnTunnelService? = instance
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var singBoxProcess: Process? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val serverId = intent.getLongExtra("server_id", 0)
                val serverName = intent.getStringExtra("server_name") ?: "VPN"
                Log.d(TAG, "ACTION_CONNECT: server=$serverName (id=$serverId)")
                startVpnWithServer(serverId, serverName)
            }
            ACTION_DISCONNECT -> {
                Log.d(TAG, "ACTION_DISCONNECT")
                stopVpn()
            }
            else -> {
                Log.d(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_STICKY
    }

    private fun startVpnWithServer(serverId: Long, serverName: String) {
        serviceScope.launch {
            try {
                // Build TUN interface
                val builder = Builder()
                builder.setSession("WhiteHole - $serverName")
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(1500)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                vpnInterface = builder.establish()

                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface")
                    stopSelf()
                    return@launch
                }

                showNotification("Connected to $serverName")
                Log.d(TAG, "VPN interface established for $serverName")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                stopVpn()
            }
        }
    }

    fun isRunning(): Boolean = vpnInterface != null

    fun startVpn(server: ServerConfig, configGenerator: ConfigGenerator): Boolean {
        return try {
            // Build TUN interface
            val builder = Builder()
            builder.setSession("WhiteHole - ${server.name}")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(1500)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return false
            }

            showNotification("Connected to ${server.name}")
            Log.d(TAG, "VPN started for server: ${server.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            false
        }
    }

    fun stopVpn() {
        try {
            singBoxProcess?.destroy()
            singBoxProcess = null
            vpnInterface?.close()
            vpnInterface = null
            hideNotification()
            Log.d(TAG, "VPN stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows VPN connection status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(text: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhiteHole")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun hideNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopVpn()
        serviceScope.cancel()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
