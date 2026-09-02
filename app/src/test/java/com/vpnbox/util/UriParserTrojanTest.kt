package com.vpnbox.util

import org.junit.Test
import org.junit.Assert.*
import android.net.Uri

/**
 * Diagnostic test for Trojan URL parsing.
 *
 * Tests the EXACT URL provided by the user to verify all fields are parsed correctly.
 *
 * URL: trojan://DTekf-K*5jTFfI%2Cy@www.speedtest.net:443?path=%2Ftr%2FSkGKJIyP7Lv0M3ga1%3Fed%3D2560&security=tls&alpn=http%2F1.1&insecure=0&host=egv8gwxm6sp3wizjt77fczhlda6xlk5.abahoauh4fjqhrfaf4-atmf.workers.dev&fp=chrome&type=ws&allowInsecure=0&sni=eGv8gwxM6SP3wizjt77fcZHlDa6XlK5.ABaHOaUh4fJQhRFAF4-atMf.WORkErS.DeV
 */
class UriParserTrojanTest {

    private val testUrl = "trojan://DTekf-K*5jTFfI%2Cy@www.speedtest.net:443" +
        "?path=%2Ftr%2FSkGKJIyP7Lv0M3ga1%3Fed%3D2560" +
        "&security=tls" +
        "&alpn=http%2F1.1" +
        "&insecure=0" +
        "&host=egv8gwxm6sp3wizjt77fczhlda6xlk5.abahoauh4fjqhrfaf4-atmf.workers.dev" +
        "&fp=chrome" +
        "&type=ws" +
        "&allowInsecure=0" +
        "&sni=eGv8gwxM6SP3wizjt77fcZHlDa6XlK5.ABaHOaUh4fJQhRFAF4-atMf.WORkErS.DeV"

    @Test
    fun `trojan URL parses all fields correctly`() {
        val config = UriParser.parse(testUrl)
        assertNotNull("Parser should return a config", config)

        config!!

        // ── Core fields ──
        assertEquals("protocol", "trojan", config.protocol.name.lowercase())
        assertEquals("address", "www.speedtest.net", config.address)
        assertEquals("port", 443, config.port)
        assertEquals("password", "DTekf-K*5jTFfI,y", config.password)

        // ── Transport ──
        assertEquals("network", "ws", config.network)

        // ── TLS fields ──
        assertEquals("sni", "eGv8gwxM6SP3wizjt77fcZHlDa6XlK5.ABaHOaUh4fJQhRFAF4-atMf.WORkErS.DeV", config.sni)
        assertEquals("fingerprint", "chrome", config.fingerprint)
        assertEquals("alpn", "http/1.1", config.alpn)
        assertEquals("allowInsecure", false, config.allowInsecure)

        // ── WebSocket transport ──
        assertEquals("wsHost", "egv8gwxm6sp3wizjt77fczhlda6xlk5.abahoauh4fjqhrfaf4-atmf.workers.dev", config.wsHost)
        assertEquals("wsPath", "/tr/SkGKJIyP7Lv0M3ga1?ed=2560", config.wsPath)

        // ── Unused fields should be null ──
        assertNull("uuid should be null for trojan", config.uuid)
        assertNull("grpcServiceName should be null for ws", config.grpcServiceName)
    }

    @Test
    fun `parseTrojan does not leak values from previous config`() {
        // Parse a DIFFERENT trojan URL first
        val otherUrl = "trojan://otherpass@other.server.com:8443?security=tls&sni=other.sni.com&type=tcp"
        val otherConfig = UriParser.parse(otherUrl)
        assertNotNull(otherConfig)
        assertEquals("otherpass", otherConfig!!.password)
        assertEquals("other.server.com", otherConfig.address)

        // Now parse our target URL
        val config = UriParser.parse(testUrl)
        assertNotNull(config)

        // CRITICAL: values must NOT leak from the first parse
        assertEquals("password must NOT be from other URL",
            "DTekf-K*5jTFfI,y", config!!.password)
        assertEquals("address must NOT be from other URL",
            "www.speedtest.net", config.address)
        assertEquals("sni must NOT be from other URL",
            "eGv8gwxM6SP3wizjt77fcZHlDa6XlK5.ABaHOaUh4fJQhRFAF4-atMf.WORkErS.DeV",
            config.sni)
        assertEquals("wsHost must NOT be from other URL",
            "egv8gwxm6sp3wizjt77fczhlda6xlk5.abahoauh4fjqhrfaf4-atmf.workers.dev",
            config.wsHost)
        assertEquals("wsPath must NOT be from other URL",
            "/tr/SkGKJIyP7Lv0M3ga1?ed=2560",
            config.wsPath)
    }

    @Test
    fun `URL encoded characters are properly decoded`() {
        val config = UriParser.parse(testUrl)
        assertNotNull(config)

        config!!
        // %2C → ,
        assertEquals("password decoded", "DTekf-K*5jTFfI,y", config.password)
        // %2F → /  %3F → ?  %3D → =
        assertEquals("path decoded", "/tr/SkGKJIyP7Lv0M3ga1?ed=2560", config.wsPath)
        // %2F → /
        assertEquals("alpn decoded", "http/1.1", config.alpn)
    }
}
