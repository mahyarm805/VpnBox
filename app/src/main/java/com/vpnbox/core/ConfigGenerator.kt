package com.vpnbox.core

import com.vpnbox.data.model.Protocol
import com.vpnbox.data.model.ServerConfig
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigGenerator @Inject constructor() {

    fun generateConfig(server: ServerConfig): String {
        val config = JsonObject().apply {
            addProperty("log", "{ \"level\": \"info\" }")
            add("dns", generateDnsConfig())
            add("inbounds", generateInbounds())
            add("outbounds", JsonArray().apply {
                add(generateOutbound(server))
            })
        }
        return config.toString()
    }

    private fun generateDnsConfig(): JsonObject {
        return JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", "8.8.8.8")
                    addProperty("detour", "direct")
                })
            })
        }
    }

    private fun generateInbounds(): JsonArray {
        return JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "tun")
                addProperty("tag", "tun-in")
                addProperty("interface_name", "tun0")
                addProperty("inet4_address", "172.19.0.1/30")
                addProperty("auto_route", true)
                addProperty("stack", "system")
                addProperty("sniff", true)
            })
        }
    }

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

    private fun generateVMessOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "vmess")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            add("vmess_settings", JsonObject().apply {
                add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", server.address)
                        addProperty("port", server.port)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", server.uuid ?: "")
                                addProperty("alterId", server.alterId)
                                addProperty("security", server.security)
                            })
                        })
                    })
                })
            })
            if (server.vmessTls) {
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

    private fun generateVlessOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "vless")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            add("vless_settings", JsonObject().apply {
                add("users", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("id", server.uuid ?: "")
                        addProperty("flow", server.flow ?: "")
                        addProperty("encryption", server.vlessEncryption)
                    })
                })
            })
            if (server.vlessTls) {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", server.sni ?: server.address)
                    if (server.fingerprint != null) {
                        add("utls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", server.fingerprint)
                        })
                    }
                    if (server.realityEnabled) {
                        add("reality", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("public_key", server.realityPublicKey ?: "")
                            addProperty("short_id", server.realityShortId ?: "")
                            addProperty("spider_x", server.realitySpiderX ?: "")
                        })
                    }
                })
            }
            if (server.network != "tcp") {
                add("transport", generateTransport(server))
            }
        }
    }

    private fun generateTrojanOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "trojan")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            add("trojan_settings", JsonObject().apply {
                add("users", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("password", server.password ?: "")
                    })
                })
            })
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
        }
    }

    private fun generateSocksOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "socks")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            if (server.username != null || server.password != null) {
                add("socks_settings", JsonObject().apply {
                    add("users", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("username", server.username ?: "")
                            addProperty("password", server.password ?: "")
                        })
                    })
                })
            }
        }
    }

    private fun generateHttpOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "http")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            if (server.username != null || server.password != null) {
                add("http_settings", JsonObject().apply {
                    add("users", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("username", server.username ?: "")
                            addProperty("password", server.password ?: "")
                        })
                    })
                })
            }
        }
    }

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
            if (server.obfs != null) {
                add("obfs", JsonObject().apply {
                    addProperty("type", server.obfs)
                    addProperty("password", server.obfsPassword ?: "")
                })
            }
        }
    }

    private fun generateTransport(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", server.network)
            when (server.network) {
                "ws" -> {
                    add("websocket", JsonObject().apply {
                        addProperty("path", "/")
                    })
                }
                "grpc" -> {
                    add("grpc", JsonObject().apply {
                        addProperty("service_name", "")
                    })
                }
            }
        }
    }

    fun generateChainConfig(servers: List<ServerConfig>): String {
        val outbounds = JsonArray()
        servers.forEachIndexed { index, server ->
            val tag = if (index == servers.lastIndex) "proxy" else "chain-$index"
            val outbound = generateOutbound(server)
            outbound.addProperty("tag", tag)
            outbounds.add(outbound)
        }

        val config = JsonObject().apply {
            addProperty("log", "{ \"level\": \"info\" }")
            add("dns", generateDnsConfig())
            add("inbounds", generateInbounds())
            add("outbounds", outbounds)
        }
        return config.toString()
    }
}
