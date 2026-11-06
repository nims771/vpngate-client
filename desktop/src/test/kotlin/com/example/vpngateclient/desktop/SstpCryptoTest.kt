package com.example.vpngateclient.desktop

import com.example.vpngateclient.desktop.sstp.Md4
import com.example.vpngateclient.desktop.sstp.Mschap
import kotlin.test.Test
import kotlin.test.assertEquals

/** Векторы из RFC 1320 (MD4) и RFC 2759 (MS-CHAPv2). */
class SstpCryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(h: String) = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun md4() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", hex(Md4.hash(ByteArray(0))))
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex(Md4.hash("abc".toByteArray())))
        assertEquals(
            "e33b4ddc9c38f2199c3e7b164fcc0536",
            hex(Md4.hash("12345678901234567890123456789012345678901234567890123456789012345678901234567890".toByteArray())),
        )
    }

    @Test
    fun mschapV2NtResponse() {
        val m = Mschap(
            user = "User",
            password = "clientPass",
            authChallenge = bytes("5B5D7C7D7B3F2F3E3C2C602132262628"),
            peerChallenge = bytes("21402324255E262A28295F2B3A337C7E"),
        )
        assertEquals("82309ecd8d708b5ea08faa3981cd83544233114a3d85d6df", hex(m.ntResponse))
    }
}
