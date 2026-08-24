package com.vpnbox.core

import android.app.Notification
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnTunnelService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VpnTunnelService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var singBoxService: SingBoxService? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        createNotificationChannel()
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
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun startVpn(server: ServerConfig, configGenerator: ConfigGenerator): Boolean {
        return try {
            val config = configGenerator.generateConfig(server)
            singBoxService = SingBoxService(context)

            serviceScope.launch {
                singBoxService?.start(config)
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
        serviceScope.launch {
            singBoxService?.stop()
        }
        vpnInterface?.close()
        vpnInterface = null
        hideNotification()
        Log.d(TAG, "VPN stopped")
    }

    private fun showNotification(text: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("VpnBox")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun hideNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    fun isRunning(): Boolean = singBoxService?.isRunning() ?: false

    fun destroy() {
        stopVpn()
        serviceScope.cancel()
    }
}
