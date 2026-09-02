package com.vpnbox.core

import com.vpnbox.data.model.Protocol
import com.vpnbox.data.model.ServerConfig
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigGenerator @Inject constructor() {

    private var lastConfig: String = ""

    /**
     * Returns the last generated sing-box JSON config as a formatted string.
     */
    fun getLastConfig(): String = lastConfig

    fun generateConfig(server: ServerConfig): String {
        val config = JsonObject().apply {
            add("log", JsonObject().apply {
                addProperty("level", "info")
                addProperty("timestamp", true)
            })
            add("dns", generateDnsConfig())
            add("inbounds", generateInbounds())
            add("outbounds", JsonArray().apply {
                add(generateOutbound(server))
                add(directOutbound())
                add(dnsOutbound())
            })
            add("route", generateRoute())
        }
        lastConfig = config.toString()
        return lastConfig
    }

    fun generateChainConfig(servers: List<ServerConfig>): String {
        val outbounds = JsonArray()
        servers.forEachIndexed { index, server ->
            val tag = if (index == servers.lastIndex) "proxy" else "chain-$index"
            val outbound = generateOutbound(server)
            outbound.addProperty("tag", tag)
            outbounds.add(outbound)
        }
        outbounds.add(directOutbound())
        outbounds.add(dnsOutbound())

        val config = JsonObject().apply {
            add("log", JsonObject().apply {
                addProperty("level", "info")
                addProperty("timestamp", true)
            })
            add("dns", generateDnsConfig())
            add("inbounds", generateInbounds())
            add("outbounds", outbounds)
            add("route", generateRoute())
        }
        lastConfig = config.toString()
        return lastConfig
    }

    // ── DNS ───────────────────────────────────────────────────────────────

    private fun generateDnsConfig(): JsonObject {
        return JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", "https://8.8.8.8/dns-query")
                    addProperty("detour", "proxy")
                })
                add(JsonObject().apply {
                    addProperty("address", "8.8.8.8")
                    addProperty("detour", "proxy")
                })
            })
            add("rules", JsonArray().apply {
                add(JsonObject().apply {
                    add("outbound", JsonArray().apply { add("any") })
                    addProperty("server", "dns-out")
                })
            })
        }
    }

    // ── Inbounds (TUN) ───────────────────────────────────────────────────

    private fun generateInbounds(): JsonArray {
        return JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "tun")
                addProperty("tag", "tun-in")
                addProperty("inet4_address", "172.19.0.1/30")
                addProperty("auto_route", true)
                addProperty("strict_route", true)
                addProperty("stack", "system")
                addProperty("sniff", true)
                addProperty("sniff_override_destination", true)
            })
        }
    }

    // ── Route ─────────────────────────────────────────────────────────────

    private fun generateRoute(): JsonObject {
        return JsonObject().apply {
            add("rules", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("protocol", "dns")
                    addProperty("outbound", "dns-out")
                })
                add(JsonObject().apply {
                    addProperty("ip_is_private", true)
                    addProperty("outbound", "direct")
                })
            })
            addProperty("auto_detect_interface", true)
            addProperty("final", "proxy")
        }
    }

    // ── Static outbounds ──────────────────────────────────────────────────

    private fun directOutbound(): JsonObject {
        return JsonObject().apply {
            addProperty("type", "direct")
            addProperty("tag", "direct")
        }
    }

    private fun dnsOutbound(): JsonObject {
        return JsonObject().apply {
            addProperty("type", "dns")
            addProperty("tag", "dns-out")
        }
    }

    // ── Dispatch by protocol ──────────────────────────────────────────────

    private fun generateOutbound(server: ServerConfig): JsonObject {
        return when (server.protocol) {
            Protocol.SHADOWSOCKS -> generateShadowsocksOutbound(server)
            Protocol.VMESS -> generateVMessOutbound(server)
            Protocol.VLESS -> generateVlessOutbound(server)
            Protocol.TROJAN -> generateTrojanOutbound(server)
            Protocol.SOCKS4, Protocol.SOCKS5 -> generateSocksOutbound(server)
            Protocol.HTTP -> generateHttpOutbound(server)
            Protocol.HYSTERIA2 -> generateHysteria2Outbound(server)
        }
    }

    // ── Shadowsocks ───────────────────────────────────────────────────────

    private fun generateShadowsocksOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "shadowsocks")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            addProperty("method", server.ssMethod)
            addProperty("password", server.password ?: "")
        }
    }

    // ── VMess (flat sing-box format) ─────────────────────────────────────

    private fun generateVMessOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "vmess")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            addProperty("uuid", server.uuid ?: "")
            addProperty("alter_id", server.alterId)
            addProperty("security", server.security)
            addProperty("global_padding", false)
            addProperty("authenticated_length", true)

            if (server.vmessTls) {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", server.sni ?: server.address)
                    addProperty("insecure", false)
                    if (server.fingerprint != null) {
                        add("utls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", server.fingerprint)
                        })
                    }
                })
            }

            if (server.network != "tcp") {
                add("transport", generateTransport(server))
            }
        }
    }

    // ── VLESS (flat sing-box format) ─────────────────────────────────────

    private fun generateVlessOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "vless")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            addProperty("uuid", server.uuid ?: "")
            if (server.vlessEncryption != "none") {
                addProperty("encryption", server.vlessEncryption)
            }
            if (!server.flow.isNullOrEmpty()) {
                addProperty("flow", server.flow)
            }

            if (server.vlessTls) {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", server.sni ?: server.address)
                    if (server.realityEnabled) {
                        add("reality", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("public_key", server.realityPublicKey ?: "")
                            addProperty("short_id", server.realityShortId ?: "")
                        })
                    }
                    if (server.fingerprint != null) {
                        add("utls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", server.fingerprint)
                        })
                    }
                })
            }

            if (server.network != "tcp") {
                add("transport", generateTransport(server))
            }
        }
    }

    // ── Trojan (flat sing-box format) ────────────────────────────────────

    private fun generateTrojanOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "trojan")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            addProperty("password", server.password ?: "")

            if (server.trojanTls) {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", server.sni ?: server.address)
                    if (server.fingerprint != null) {
                        add("utls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", server.fingerprint)
                        })
                    }
                })
            }

            if (server.network != "tcp") {
                add("transport", generateTransport(server))
            }
        }
    }

    // ── SOCKS (sing-box flat format) ─────────────────────────────────────

    private fun generateSocksOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "socks")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            if (!server.username.isNullOrEmpty()) {
                addProperty("username", server.username)
            }
            if (!server.password.isNullOrEmpty()) {
                addProperty("password", server.password)
            }
        }
    }

    // ── HTTP (sing-box flat format) ──────────────────────────────────────

    private fun generateHttpOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "http")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            if (!server.username.isNullOrEmpty()) {
                addProperty("username", server.username)
            }
            if (!server.password.isNullOrEmpty()) {
                addProperty("password", server.password)
            }
            add("tls", JsonObject().apply {
                addProperty("enabled", false)
            })
        }
    }

    // ── Hysteria2 ─────────────────────────────────────────────────────────

    private fun generateHysteria2Outbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "hysteria2")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            addProperty("password", server.password ?: "")
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", server.sni ?: server.address)
            })
            if (!server.obfs.isNullOrEmpty()) {
                add("obfs", JsonObject().apply {
                    addProperty("type", server.obfs)
                    addProperty("password", server.obfsPassword ?: "")
                })
            }
        }
    }

    // ── Transport (flat sing-box format) ──────────────────────────────────

    private fun generateTransport(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", server.network)
            when (server.network) {
                "ws" -> {
                    addProperty("path", server.wsPath ?: "/")
                    if (!server.wsHost.isNullOrEmpty()) {
                        add("headers", JsonObject().apply {
                            addProperty("Host", server.wsHost)
                        })
                    }
                }
                "grpc" -> {
                    addProperty("service_name", server.grpcServiceName ?: "")
                }
            }
        }
    }
}
