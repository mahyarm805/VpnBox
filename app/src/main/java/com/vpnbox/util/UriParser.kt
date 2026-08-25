package com.vpnbox.util

import android.net.Uri
import android.util.Base64
import com.vpnbox.data.model.Protocol
import com.vpnbox.data.model.ServerConfig
import com.google.gson.JsonParser
import java.net.URLDecoder

object UriParser {

    fun parse(uri: String): ServerConfig? {
        return try {
            when {
                uri.startsWith("vmess://") -> parseVMess(uri)
                uri.startsWith("ss://") -> parseShadowsocks(uri)
                uri.startsWith("vless://") -> parseVless(uri)
                uri.startsWith("trojan://") -> parseTrojan(uri)
                uri.startsWith("hysteria2://") || uri.startsWith("hy2://") -> parseHysteria2(uri)
                uri.startsWith("socks5://") -> parseSocks(uri, Protocol.SOCKS5)
                uri.startsWith("socks4://") -> parseSocks(uri, Protocol.SOCKS4)
                uri.startsWith("socks://") -> parseSocks(uri, Protocol.SOCKS5)
                uri.startsWith("http://") -> parseHttpProxy(uri, Protocol.HTTP)
                uri.startsWith("https://") -> parseHttpProxy(uri, Protocol.HTTP)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ======================== VMess ========================

    private fun parseVMess(uri: String): ServerConfig {
        val encoded = uri.removePrefix("vmess://")
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
        val json = JsonParser.parseString(decoded).asJsonObject

        val tls = json.get("tls")?.asString == "tls"
        val sni = json.get("sni")?.asString
        val fp = json.get("fp")?.asString
        val alpn = json.get("alpn")?.asString
        val allowInsecure = json.get("allowInsecure")?.asString == "1"
        val host = json.get("host")?.asString
        val path = json.get("path")?.asString
        val net = json.get("net")?.asString ?: "tcp"

        val name = json.get("ps")?.asString?.takeIf { it.isNotBlank() }
            ?: sni?.let { "VMess - $it" }
            ?: json.get("add")?.asString?.let { "VMess - $it" }
            ?: "VMess Server"

        return ServerConfig(
            name = name,
            protocol = Protocol.VMESS,
            address = json.get("add")?.asString ?: "",
            port = json.get("port")?.asInt ?: 443,
            uuid = json.get("id")?.asString,
            alterId = json.get("aid")?.asInt ?: 0,
            security = json.get("scy")?.asString ?: "auto",
            network = net,
            vmessTls = tls,
            sni = sni,
            fingerprint = fp,
            alpn = alpn,
            allowInsecure = allowInsecure,
            // Transport fields based on type
            wsHost = if (net == "ws") host else null,
            wsPath = if (net == "ws") path else null,
            grpcServiceName = if (net == "grpc") path else null,
            h2Host = if (net == "h2") host else null,
            h2Path = if (net == "h2") path else null,
            xhttpMode = if (net == "xhttp" || net == "http") json.get("mode")?.asString else null,
            xhttpPath = if (net == "xhttp" || net == "http") path else null
        )
    }

    // ======================== Shadowsocks ========================

    private fun parseShadowsocks(uri: String): ServerConfig {
        val data = uri.removePrefix("ss://")

        // Try to extract fragment (name) first
        val nameFromFragment: String?
        val dataWithoutFragment: String
        if (data.contains("#")) {
            val hashIndex = data.indexOf("#")
            nameFromFragment = URLDecoder.decode(data.substring(hashIndex + 1), "UTF-8")
            dataWithoutFragment = data.substring(0, hashIndex)
        } else {
            nameFromFragment = null
            dataWithoutFragment = data
        }

        // Handle the @ separator - some URIs have base64-encoded method:password before @
        if (dataWithoutFragment.contains("@")) {
            return parseShadowsocksWithUser(dataWithoutFragment, nameFromFragment)
        }

        // Everything before the @ might be base64-encoded (includes the @ sign)
        // Try decoding the whole string
        val decoded = tryDecodeBase64(dataWithoutFragment) ?: dataWithoutFragment
        if (decoded.contains("@")) {
            return parseShadowsocksWithUser(decoded, nameFromFragment)
        }

        return ServerConfig(
            name = nameFromFragment ?: "SS Server",
            protocol = Protocol.SHADOWSOCKS,
            address = "",
            port = 8388
        )
    }

    private fun parseShadowsocksWithUser(data: String, name: String?): ServerConfig {
        val atIdx = data.lastIndexOf("@")
        val userInfoPart = data.substring(0, atIdx)
        val serverPart = data.substring(atIdx + 1)

        // userInfo can be base64(method:password) or method:password
        val userInfo = tryDecodeBase64(userInfoPart)?.split(":")
            ?: userInfoPart.split(":")

        val method = userInfo.getOrElse(0) { "aes-256-gcm" }
        val password = userInfo.getOrElse(1) { "" }

        // Server part may have :port and optional #fragment
        val colonIdx = serverPart.lastIndexOf(":")
        val address: String
        val port: Int

        if (colonIdx > 0) {
            address = serverPart.substring(0, colonIdx)
            port = serverPart.substring(colonIdx + 1).toIntOrNull() ?: 8388
        } else {
            address = serverPart
            port = 8388
        }

        return ServerConfig(
            name = name ?: "SS Server",
            protocol = Protocol.SHADOWSOCKS,
            address = address,
            port = port,
            password = password,
            ssMethod = method
        )
    }

    // ======================== VLESS ========================

    private fun parseVless(uri: String): ServerConfig {
        val uriObj = Uri.parse(uri)
        val uuid = uriObj.userInfo ?: ""
        val address = uriObj.host ?: ""
        val port = uriObj.port.takeIf { it > 0 } ?: 443

        // Extract fragment as name
        val fragment = uriObj.fragment
        val name = fragment?.takeIf { it.isNotBlank() }?.let { URLDecoder.decode(it, "UTF-8") }

        // Query parameters
        val type = uriObj.getQueryParameter("type") ?: "tcp"
        val sni = uriObj.getQueryParameter("sni")
        val fp = uriObj.getQueryParameter("fp")
        val alpn = uriObj.getQueryParameter("alpn")
        val flow = uriObj.getQueryParameter("flow")
        val security = uriObj.getQueryParameter("security") ?: "tls"
        val host = uriObj.getQueryParameter("host")
        val path = uriObj.getQueryParameter("path")
        val pbk = uriObj.getQueryParameter("pbk")
        val sid = uriObj.getQueryParameter("sid")
        val spx = uriObj.getQueryParameter("spx")
        val allowInsecure = uriObj.getQueryParameter("allowInsecure") == "1"
            || uriObj.getQueryParameter("allowInsecure") == "true"

        return ServerConfig(
            name = name ?: sni?.let { "VLESS - $it" } ?: "VLESS Server",
            protocol = Protocol.VLESS,
            address = address,
            port = port,
            uuid = uuid,
            sni = sni,
            fingerprint = fp,
            flow = flow,
            vlessEncryption = "none",
            vlessTls = security == "tls" || security == "reality",
            realityEnabled = security == "reality",
            realityPublicKey = pbk,
            realityShortId = sid,
            realitySpiderX = spx,
            alpn = alpn,
            allowInsecure = allowInsecure,
            // Transport fields
            network = type,
            wsHost = if (type == "ws") host else null,
            wsPath = if (type == "ws") path else null,
            grpcServiceName = if (type == "grpc") path else null,
            h2Host = if (type == "h2") host else null,
            h2Path = if (type == "h2") path else null,
            xhttpMode = if (type == "xhttp") uriObj.getQueryParameter("mode") else null,
            xhttpPath = if (type == "xhttp") path else null
        )
    }

    // ======================== Trojan ========================

    private fun parseTrojan(uri: String): ServerConfig {
        val uriObj = Uri.parse(uri)
        val password = uriObj.userInfo ?: ""
        val address = uriObj.host ?: ""
        val port = uriObj.port.takeIf { it > 0 } ?: 443

        // Extract fragment as name
        val fragment = uriObj.fragment
        val name = fragment?.takeIf { it.isNotBlank() }?.let { URLDecoder.decode(it, "UTF-8") }

        // Query parameters
        val type = uriObj.getQueryParameter("type") ?: "tcp"
        val security = uriObj.getQueryParameter("security") ?: "tls"
        val sni = uriObj.getQueryParameter("sni")
        val fp = uriObj.getQueryParameter("fp")
        val alpn = uriObj.getQueryParameter("alpn")
        val host = uriObj.getQueryParameter("host")
        val path = uriObj.getQueryParameter("path")
        val allowInsecure = uriObj.getQueryParameter("allowInsecure") == "1"
            || uriObj.getQueryParameter("allowInsecure") == "true"

        return ServerConfig(
            name = name ?: sni?.let { "Trojan - $it" } ?: "Trojan Server",
            protocol = Protocol.TROJAN,
            address = address,
            port = port,
            password = password,
            sni = sni,
            fingerprint = fp,
            trojanTls = security != "none",
            alpn = alpn,
            allowInsecure = allowInsecure,
            // Transport fields
            network = type,
            wsHost = if (type == "ws") host else null,
            wsPath = if (type == "ws") path else null,
            grpcServiceName = if (type == "grpc") path else null,
            h2Host = if (type == "h2") host else null,
            h2Path = if (type == "h2") path else null,
            xhttpMode = if (type == "xhttp") uriObj.getQueryParameter("mode") else null,
            xhttpPath = if (type == "xhttp") path else null
        )
    }

    // ======================== SOCKS ========================

    private fun parseSocks(uri: String, protocol: Protocol): ServerConfig {
        val scheme = when {
            uri.startsWith("socks5://") -> "socks5://"
            uri.startsWith("socks4://") -> "socks4://"
            else -> "socks://"
        }
        val rest = uri.removePrefix(scheme)

        // Extract fragment as name
        val name: String?
        val data: String
        if (rest.contains("#")) {
            val hashIdx = rest.indexOf("#")
            name = URLDecoder.decode(rest.substring(hashIdx + 1), "UTF-8")
            data = rest.substring(0, hashIdx)
        } else {
            name = null
            data = rest
        }

        // Split off query params if any
        val pathAndQuery = data
        val queryStr = if (pathAndQuery.contains("?")) {
            pathAndQuery.substring(pathAndQuery.indexOf("?") + 1)
        } else {
            null
        }
        val authority = if (pathAndQuery.contains("?")) {
            pathAndQuery.substring(0, pathAndQuery.indexOf("?"))
        } else {
            pathAndQuery
        }

        // Parse authority: [user[:password]@]host:port
        val atIdx = authority.indexOf("@")
        val username: String?
        val password: String?
        val hostPort: String

        if (atIdx >= 0) {
            val userPart = authority.substring(0, atIdx)
            hostPort = authority.substring(atIdx + 1)

            // userPart can be "user:pass" or base64("user:pass")
            val decodedUserPart = tryDecodeBase64(userPart)
            val userParts = (decodedUserPart ?: userPart).split(":")
            username = userParts.getOrElse(0) { null }
            password = userParts.getOrElse(1) { null }
        } else {
            hostPort = authority
            username = null
            password = null
        }

        val colonIdx = hostPort.lastIndexOf(":")
        val address: String
        val port: Int

        if (colonIdx > 0) {
            address = hostPort.substring(0, colonIdx)
            port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: protocol.defaultPort
        } else {
            address = hostPort
            port = protocol.defaultPort
        }

        return ServerConfig(
            name = name ?: "$address:$port",
            protocol = protocol,
            address = address,
            port = port,
            password = password,
            username = username
        )
    }

    // ======================== HTTP Proxy ========================

    private fun parseHttpProxy(uri: String, protocol: Protocol): ServerConfig {
        val rest = uri.removePrefix("http://").removePrefix("https://")

        // Extract fragment as name
        val name: String?
        val data: String
        if (rest.contains("#")) {
            val hashIdx = rest.indexOf("#")
            name = URLDecoder.decode(rest.substring(hashIdx + 1), "UTF-8")
            data = rest.substring(0, hashIdx)
        } else {
            name = null
            data = rest
        }

        // Parse authority: [user[:password]@]host:port
        val atIdx = data.indexOf("@")
        val username: String?
        val password: String?
        val hostPort: String

        if (atIdx >= 0) {
            val userPart = data.substring(0, atIdx)
            hostPort = data.substring(atIdx + 1)

            val decodedUserPart = tryDecodeBase64(userPart)
            val userParts = (decodedUserPart ?: userPart).split(":")
            username = userParts.getOrElse(0) { null }
            password = userParts.getOrElse(1) { null }
        } else {
            hostPort = data
            username = null
            password = null
        }

        val colonIdx = hostPort.lastIndexOf(":")
        val address: String
        val port: Int

        if (colonIdx > 0) {
            address = hostPort.substring(0, colonIdx)
            port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: protocol.defaultPort
        } else {
            address = hostPort
            port = protocol.defaultPort
        }

        return ServerConfig(
            name = name ?: "$address:$port",
            protocol = protocol,
            address = address,
            port = port,
            password = password,
            username = username
        )
    }

    // ======================== Hysteria2 ========================

    private fun parseHysteria2(uri: String): ServerConfig {
        val uriObj = Uri.parse(uri)
        val password = uriObj.userInfo ?: ""
        val address = uriObj.host ?: ""
        val port = uriObj.port.takeIf { it > 0 } ?: 443

        // Extract fragment as name
        val fragment = uriObj.fragment
        val name = fragment?.takeIf { it.isNotBlank() }?.let { URLDecoder.decode(it, "UTF-8") }

        // Query parameters
        val sni = uriObj.getQueryParameter("sni")
        val obfsType = uriObj.getQueryParameter("obfs")
        val obfsPassword = uriObj.getQueryParameter("obfs-password")
            ?: uriObj.getQueryParameter("obfs_password")
        val insecure = uriObj.getQueryParameter("insecure") == "1"
            || uriObj.getQueryParameter("insecure") == "true"
            || uriObj.getQueryParameter("allowInsecure") == "1"
            || uriObj.getQueryParameter("allowInsecure") == "true"

        return ServerConfig(
            name = name ?: sni?.let { "Hysteria2 - $it" } ?: "Hysteria2 Server",
            protocol = Protocol.HYSTERIA2,
            address = address,
            port = port,
            password = password,
            sni = sni,
            obfs = obfsType,
            obfsPassword = obfsPassword,
            allowInsecure = insecure
        )
    }

    // ======================== Helpers ========================

    /**
     * Try to decode a string as base64 (standard or URL-safe).
     * Returns the decoded string on success, or null on failure.
     */
    private fun tryDecodeBase64(input: String): String? {
        return try {
            // Try URL-safe base64 first (common in SS URIs)
            val decoded = Base64.decode(input, Base64.URL_SAFE)
            String(decoded)
        } catch (_: Exception) {
            try {
                // Try standard base64
                val decoded = Base64.decode(input, Base64.DEFAULT)
                String(decoded)
            } catch (_: Exception) {
                null
            }
        }
    }
}
