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
    HYSTERIA2("Hysteria2", 443)
}

@Entity(tableName = "servers")
data class ServerConfig(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    // Common
    val password: String? = null,
    val sni: String? = null,
    val fingerprint: String? = null,
    val country: String? = null,
    val isSelected: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // VMess
    val uuid: String? = null,
    val alterId: Int = 0,
    val security: String = "auto",
    val network: String = "tcp",
    val vmessTls: Boolean = false,
    // VLESS
    val flow: String? = null,
    val vlessEncryption: String = "none",
    val vlessTls: Boolean = true,
    val realityEnabled: Boolean = false,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val realitySpiderX: String? = null,
    // Trojan
    val trojanTls: Boolean = true,
    // Shadowsocks
    val ssMethod: String = "aes-256-gcm",
    // SOCKS/HTTP
    val username: String? = null,
    // Hysteria2
    val obfs: String? = null,
    val obfsPassword: String? = null
)
