package com.vpnbox.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Protocol(val displayName: String, val defaultPort: Int) {
    SHADOWSOCKS("Shadowsocks", 8388),
    VMESS("VMess", 443),
    VLESS("VLESS", 443),
    TROJAN("Trojan", 443),
    SOCKS4("SOCKS4", 1080),
    SOCKS5("SOCKS5", 1080),
    HTTP("HTTP", 8080),
    QUIC("QUIC", 443)
}

@Entity(tableName = "servers")
data class ServerConfig(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    val password: String? = null,
    val uuid: String? = null,
    val alterId: Int = 0,
    val security: String = "auto",
    val network: String = "tcp",
    val sni: String? = null,
    val fingerprint: String? = null,
    val flow: String? = null,
    val country: String? = null,
    val isSelected: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
