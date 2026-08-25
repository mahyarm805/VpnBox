import com.vpnbox.data.model.Protocol
import com.vpnbox.data.model.ServerConfig
import com.google.gson.JsonObject
import com.google.gson.JsonArray

class ConfigGenerator {

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
            addProperty(
                "servers",
                "[{\"address\": \"8.8.8.8\", \"detour\": \"direct\"}]"
            )
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
            Protocol.QUIC -> generateQuicOutbound(server)
        }
    }

    private fun generateShadowsocksOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "shadowsocks")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            addProperty("method", server.security ?: "")
            addProperty("password", server.password ?: "")
        }
    }

    private fun generateVMessOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "vmess")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            addProperty("uuid", server.uuid ?: "")
            addProperty("security", server.security ?: "")
            addProperty("alter_id", server.alterId)
            add("vmess_settings", JsonObject().apply {
                add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", server.address)
                        addProperty("port", server.port)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", server.uuid ?: "")
                                addProperty("alterId", server.alterId)
                                addProperty("security", server.security ?: "")
                            })
                        })
                    })
                })
            })
        }
    }

    private fun generateVlessOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "vless")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            addProperty("uuid", server.uuid ?: "")
            addProperty("flow", server.flow ?: "")
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", server.sni ?: server.address)

                if (server.fingerprint != null) {
                    add("utls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("fingerprint", server.fingerprint ?: "")
                    })
                }
            })
        }
    }

    private fun generateTrojanOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "trojan")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            addProperty("password", server.password ?: "")
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", server.sni ?: server.address)
            })
        }
    }

    private fun generateSocksOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "socks")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)

            if (server.password != null) {
                addProperty("username", server.uuid ?: "")
                addProperty("password", server.password ?: "")
            }
        }
    }

    private fun generateHttpOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "http")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)

            if (server.password != null) {
                addProperty("username", server.uuid ?: "")
                addProperty("password", server.password ?: "")
            }
        }
    }

    private fun generateQuicOutbound(server: ServerConfig): JsonObject {
        return JsonObject().apply {
            addProperty("type", "hysteria2")
            addProperty("tag", "proxy")
            addProperty("server", server.address)
            addProperty("server_port", server.port)
            addProperty("password", server.password ?: "")
            addProperty("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", server.sni ?: server.address)
            })
        }
    }

    fun generateChainConfig(servers: List<ServerConfig>): String {
        val outbounds = JsonArray()

        servers.forEachIndexed { index, server ->
            val tag = if (index == servers.lastIndex) {
                "proxy"
            } else {
                "chain-$index"
            }

            outbounds.add(generateOutboundForChain(server, tag))
        }

        val config = JsonObject().apply {
            addProperty("log", "{ \"level\": \"info\" }")
            add("dns", generateDnsConfig())
            add("inbounds", generateInbounds())
            add("outbounds", outbounds)
        }

        return config.toString()
    }

    private fun generateOutboundForChain(
        server: ServerConfig,
        tag: String
    ): JsonObject {
        val outbound = generateOutbound(server)
        outbound.addProperty("tag", tag)
        return outbound
    }
}
