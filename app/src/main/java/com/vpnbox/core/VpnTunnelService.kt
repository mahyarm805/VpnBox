package com.vpnbox.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vpnbox.MainActivity
import com.vpnbox.R
import com.vpnbox.data.model.ServerConfig
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class VpnTunnelService : VpnService() {

    companion object {
        private const val TAG = "VpnTunnelService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1

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
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopVpn()
        serviceScope.cancel()
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

    fun startVpn(server: ServerConfig, configGenerator: ConfigGenerator): Boolean {
        return try {
            val config = configGenerator.generateConfig(server)

            val configFile = File(filesDir, "sing-box-config.json")
            FileOutputStream(configFile).use { fos ->
                fos.write(config.toByteArray())
            }

            val processBuilder = ProcessBuilder(
                "sing-box", "run", "-c", configFile.absolutePath
            )
            processBuilder.redirectErrorStream(true)
            processBuilder.directory(filesDir)

            singBoxProcess = processBuilder.start()

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

    private fun showNotification(text: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VpnBox")
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

    fun isRunning(): Boolean = singBoxProcess?.isAlive ?: false

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
