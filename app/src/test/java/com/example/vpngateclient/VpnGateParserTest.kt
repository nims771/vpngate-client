package com.example.vpngateclient

import com.example.vpngateclient.data.VpnGateParser
import com.example.vpngateclient.data.countryFlag
import com.example.vpngateclient.vpn.OvpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VpnGateParserTest {

    private val tcpConfig = """
        ###############################################################################
        # OpenVPN 2.0 Sample Configuration File
        client
        dev tun
        proto tcp
        remote 219.100.37.10 443
        cipher AES-128-CBC
        auth SHA1
        resolv-retry infinite
        nobind
        persist-key
        persist-tun
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
        -----END CERTIFICATE-----
        </ca>
    """.trimIndent().replace("\n", "\r\n")

    private val udpConfig = "client\r\ndev tun\r\nproto udp\r\nremote 1.2.3.4 1195\r\ncipher AES-128-CBC\r\nauth-user-pass\r\n"

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private val csv = buildString {
        append("*vpn_servers\r\n")
        append("#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\r\n")
        append("public-vpn-10,219.100.37.10,1234567,5,95000000,Japan,JP,42,86400000,1000,5000000000,2weeks,Daiyuu Nobori_ Japan. Academic Use Only.,,${b64(tcpConfig)}\r\n")
        append("vpn123,1.2.3.4,900,20,12000000,Korea Republic of,KR,3,3600000,50,1000,2weeks,Someone,Hello, world,${b64(udpConfig)}\r\n")
        append("broken,5.6.7.8,1,1,1,X,XX,0,0,0,0,,,,\r\n")
        append("*\r\n")
    }

    @Test
    fun parsesServers() {
        val list = VpnGateParser.parse(csv)
        assertEquals(2, list.size)

        val jp = list[0]
        assertEquals("public-vpn-10", jp.hostName)
        assertEquals("JP", jp.countryShort)
        assertEquals(95_000_000L, jp.speedBps)
        assertEquals("tcp", jp.proto)
        assertEquals(443, jp.port)
        assertTrue(jp.config.contains("BEGIN CERTIFICATE"))

        val kr = list[1]
        assertEquals("udp", kr.proto)
        assertEquals(1195, kr.port)
        assertEquals("Hello, world", kr.message) // запятая в сообщении не ломает разбор
    }

    @Test
    fun prepareAddsDataCiphers() {
        val out = OvpnConfig.prepare(tcpConfig)
        assertFalse(out.contains('\r'))
        assertTrue(out.contains("data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-128-CBC"))
        assertTrue(out.contains("data-ciphers-fallback AES-128-CBC"))
        // повторная подготовка не дублирует строки
        assertEquals(out, OvpnConfig.prepare(out))
    }

    @Test
    fun prepareInlinesAuth() {
        val out = OvpnConfig.prepare(udpConfig)
        assertTrue(out.contains("<auth-user-pass>\nvpn\nvpn\n</auth-user-pass>"))
    }

    @Test
    fun flags() {
        assertEquals("🇯🇵", countryFlag("jp"))
        assertEquals("🌐", countryFlag("??"))
    }
}
