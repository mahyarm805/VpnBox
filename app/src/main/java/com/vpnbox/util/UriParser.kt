package com.vpnbox.util

import android.net.Uri
import com.vpnbox.data.model.Protocol
import com.vpnbox.data.model.ServerConfig
import java.net.URLDecoder

object UriParser {

    fun parse(uri: String): ServerConfig? {
        return try {
            when {
                uri.startsWith("vmess://") -> parseVMess(uri)
                uri.startsWith("ss://") -> parseShadowsocks(uri)
                uri.startsWith("vless://") -> parseVless(uri)
                uri.startsWith("trojan://") -> parseTrojan(uri)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVMess(uri: String): ServerConfig {
        val encoded = uri.removePrefix("vmess://")
        val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        val json = com.google.gson.JsonParser.parseString(decoded).asJsonObject

        return ServerConfig(
            name = json.get("ps")?.asString ?: "VMess Server",
            protocol = Protocol.VMESS,
            address = json.get("add")?.asString ?: "",
            port = json.get("port")?.asInt ?: 443,
            uuid = json.get("id")?.asString,
            alterId = json.get("aid")?.asInt ?: 0,
            security = json.get("scy")?.asString ?: "auto",
            network = json.get("net")?.asString ?: "tcp",
            sni = json.get("sni")?.asString
        )
    }

    private fun parseShadowsocks(uri: String): ServerConfig {
        val decoded = String(android.util.Base64.decode(uri.removePrefix("ss://"), android.util.Base64.DEFAULT))
        val parts = decoded.split("@")
        val userInfo = parts[0].split(":")
        val serverInfo = parts[1].split("#")

        return ServerConfig(
            name = URLDecoder.decode(serverInfo.getOrElse(1) { "SS Server" }, "UTF-8"),
            protocol = Protocol.SHADOWSOCKS,
            address = serverInfo[0].split(":")[0],
            port = serverInfo[0].split(":")[1].toIntOrNull() ?: 8388,
            password = userInfo.getOrElse(1) { "" },
            security = userInfo[0]
        )
    }

    private fun parseVless(uri: String): ServerConfig {
        val uriObj = Uri.parse(uri)
        val userInfo = uriObj.userInfo?.split(":") ?: listOf()
        val address = uriObj.host ?: ""
        val port = uriObj.port.takeIf { it > 0 } ?: 443

        return ServerConfig(
            name = uriObj.getQueryParameter("security")?.let { "VLESS - $it" } ?: "VLESS Server",
            protocol = Protocol.VLESS,
            address = address,
            port = port,
            uuid = userInfo.getOrElse(0) { "" },
            sni = uriObj.getQueryParameter("sni"),
            fingerprint = uriObj.getQueryParameter("fp"),
            flow = uriObj.getQueryParameter("flow"),
            security = uriObj.getQueryParameter("security") ?: "tls"
        )
    }

    private fun parseTrojan(uri: String): ServerConfig {
        val uriObj = Uri.parse(uri)
        val password = uriObj.userInfo ?: ""
        val address = uriObj.host ?: ""
        val port = uriObj.port.takeIf { it > 0 } ?: 443

        return ServerConfig(
            name = uriObj.getQueryParameter("security")?.let { "Trojan - $it" } ?: "Trojan Server",
            protocol = Protocol.TROJAN,
            address = address,
            port = port,
            password = password,
            sni = uriObj.getQueryParameter("sni"),
            security = uriObj.getQueryParameter("security") ?: "tls"
        )
    }
}
