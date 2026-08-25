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
            sni = json.get("sni")?.asString,
            fingerprint = json.get("fp")?.asString,
            vmessTls = json.get("tls")?.asString == "tls"
        )
    }

    private fun parseShadowsocks(uri: String): ServerConfig {
        val data = uri.removePrefix("ss://")
        val decoded = try {
            String(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            data
        }

        val parts = decoded.split("@")
        if (parts.size < 2) return ServerConfig(
            name = "SS Server",
            protocol = Protocol.SHADOWSOCKS,
            address = "",
            port = 8388
        )

        val userInfo = parts[0].split(":")
        val serverPart = parts[1].split("#")
        val addressPort = serverPart[0].split(":")

        return ServerConfig(
            name = if (serverPart.size > 1) URLDecoder.decode(serverPart[1], "UTF-8") else "SS Server",
            protocol = Protocol.SHADOWSOCKS,
            address = addressPort[0],
            port = addressPort.getOrElse(1) { "8388" }.toIntOrNull() ?: 8388,
            password = userInfo.getOrElse(1) { "" },
            ssMethod = userInfo[0]
        )
    }

    private fun parseVless(uri: String): ServerConfig {
        val uriObj = Uri.parse(uri)
        val userInfo = uriObj.userInfo?.split(":") ?: listOf()
        val address = uriObj.host ?: ""
        val port = uriObj.port.takeIf { it > 0 } ?: 443

        val sni = uriObj.getQueryParameter("sni")
        val fp = uriObj.getQueryParameter("fp")
        val flow = uriObj.getQueryParameter("flow")
        val security = uriObj.getQueryParameter("security") ?: "tls"
        val pbk = uriObj.getQueryParameter("pbk")
        val sid = uriObj.getQueryParameter("sid")
        val spx = uriObj.getQueryParameter("spx")

        return ServerConfig(
            name = sni?.let { "VLESS - $it" } ?: "VLESS Server",
            protocol = Protocol.VLESS,
            address = address,
            port = port,
            uuid = userInfo.getOrElse(0) { "" },
            sni = sni,
            fingerprint = fp,
            flow = flow,
            vlessTls = security == "tls" || security == "reality",
            realityEnabled = security == "reality",
            realityPublicKey = pbk,
            realityShortId = sid,
            realitySpiderX = spx
        )
    }

    private fun parseTrojan(uri: String): ServerConfig {
        val uriObj = Uri.parse(uri)
        val password = uriObj.userInfo ?: ""
        val address = uriObj.host ?: ""
        val port = uriObj.port.takeIf { it > 0 } ?: 443

        val sni = uriObj.getQueryParameter("sni")
        val fp = uriObj.getQueryParameter("fp")

        return ServerConfig(
            name = sni?.let { "Trojan - $it" } ?: "Trojan Server",
            protocol = Protocol.TROJAN,
            address = address,
            port = port,
            password = password,
            sni = sni,
            fingerprint = fp,
            trojanTls = true
        )
    }
}
