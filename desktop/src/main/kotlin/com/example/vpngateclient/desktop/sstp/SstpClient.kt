package com.example.vpngateclient.desktop.sstp

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * SSTP-клиент на чистом Kotlin — тот же способ, что в Android-версии:
 * TLS без имени сервера (SNI) и без проверки сертификата, затем SSTP → PPP (PAP или MS-CHAPv2) → IPv4.
 * Логика согласования PPP и криптопривязки повторяет Open SSTP Client (MIT).
 */
class SstpClient(
    private val host: String,
    private val port: Int,
    private val user: String = "vpn",
    private val password: String = "vpn",
    private val mtu: Int = 1400,
    private val log: (String) -> Unit,
) {
    class Negotiated(val ip: Inet4Address, val dns: List<Inet4Address>)

    private lateinit var ssl: SSLSocket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private val writeLock = Any()
    private val random = SecureRandom()

    @Volatile var closed = false
        private set

    @Volatile var lastRx = System.currentTimeMillis()
        private set

    // ------------------------------------------------------------------ подключение

    /** Подключается и согласовывает PPP. Блокирует поток, бросает исключение с понятным текстом. */
    fun connect(timeoutMs: Int = 25_000): Negotiated {
        val deadline = System.currentTimeMillis() + timeoutMs
        log("SSTP: TCP $host:$port…")
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), 10_000)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(TrustAll), random)
        // имя хоста = IP-адрес: Java в этом случае SNI не отправляет; дополнительно очищаем явно
        ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { serverNames = emptyList<SNIServerName>() }
        ssl.soTimeout = 10_000
        log("SSTP: TLS (без SNI)…")
        ssl.startHandshake()
        log("SSTP: TLS установлен (${ssl.session.protocol})")
        input = DataInputStream(ssl.inputStream.buffered(65536))
        output = ssl.outputStream
        ssl.soTimeout = 3_000

        // HTTP-запрос SSTP
        val request = "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n" +
            "Content-Length: 18446744073709551615\r\n" +
            "Host: $host\r\n" +
            "SSTPCORRELATIONID: {${UUID.randomUUID().toString().uppercase()}}\r\n\r\n"
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val status = readHttpHeaders()
        if (!status.contains(" 200")) throw IOException("сервер ответил: $status")
        log("SSTP: HTTP 200")

        return negotiate(deadline)
    }

    private fun readHttpHeaders(): String {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("соединение закрыто сервером")
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.clear()
                if (line.isEmpty()) break
                lines += line
            } else sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("слишком длинный ответ")
        }
        return lines.firstOrNull() ?: ""
    }

    // ------------------------------------------------------------------ SSTP-пакеты

    private class Packet(val control: Boolean, val body: ByteArray)

    private fun readPacket(): Packet {
        val version = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        val length = input.readUnsignedShort() and 0x0FFF
        if (version != 0x10 || length < 4) throw IOException("неверный пакет SSTP")
        val body = ByteArray(length - 4)
        input.readFully(body)
        lastRx = System.currentTimeMillis()
        return Packet(flags and 1 == 1, body)
    }

    private fun writePacket(control: Boolean, body: ByteArray) {
        val len = body.size + 4
        val buf = ByteArray(len)
        buf[0] = 0x10
        buf[1] = if (control) 1 else 0
        buf[2] = ((len shr 8) and 0x0F).toByte()
        buf[3] = (len and 0xFF).toByte()
        body.copyInto(buf, 4)
        synchronized(writeLock) {
            output.write(buf)
            output.flush()
        }
    }

    private fun control(type: Int, vararg attrs: ByteArray): ByteArray {
        val size = 4 + attrs.sumOf { it.size }
        return ByteBuffer.allocate(size).apply {
            putShort(type.toShort())
            putShort(attrs.size.toShort())
            attrs.forEach { put(it) }
        }.array()
    }

    private fun sendPpp(protocol: Int, payload: ByteArray) {
        val body = ByteArray(4 + payload.size)
        body[0] = 0xFF.toByte()
        body[1] = 0x03
        body[2] = (protocol shr 8).toByte()
        body[3] = protocol.toByte()
        payload.copyInto(body, 4)
        writePacket(false, body)
    }

    /** Отправка IPv4-пакета в туннель. */
    fun sendIp(packet: ByteArray, offset: Int = 0, length: Int = packet.size) {
        val body = ByteArray(4 + length)
        body[0] = 0xFF.toByte()
        body[1] = 0x03
        body[2] = 0x00
        body[3] = 0x21
        System.arraycopy(packet, offset, body, 4, length)
        writePacket(false, body)
    }

    // ------------------------------------------------------------------ PPP

    private class Ppp(val protocol: Int, val code: Int, val id: Int, val data: ByteArray)

    /** Разбирает PPP-кадр из данных SSTP (с полями FF 03 или без них). */
    private fun parsePpp(body: ByteArray): Pair<Int, ByteArray>? {
        var off = 0
        if (body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0x03.toByte()) off = 2
        if (body.size < off + 2) return null
        var proto = body[off].toInt() and 0xFF
        // сжатое поле протокола (1 байт, нечётное)
        if (proto and 1 == 1) {
            return proto to body.copyOfRange(off + 1, body.size)
        }
        proto = (proto shl 8) or (body[off + 1].toInt() and 0xFF)
        return proto to body.copyOfRange(off + 2, body.size)
    }

    private fun parseCp(proto: Int, p: ByteArray): Ppp? {
        if (p.size < 4) return null
        val len = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        val end = len.coerceIn(4, p.size)
        return Ppp(proto, p[0].toInt() and 0xFF, p[1].toInt() and 0xFF, p.copyOfRange(4, end))
    }

    private fun cp(code: Int, id: Int, data: ByteArray): ByteArray {
        val len = 4 + data.size
        return byteArrayOf(code.toByte(), id.toByte(), (len shr 8).toByte(), len.toByte()) + data
    }

    private class Opt(val type: Int, val value: ByteArray) {
        fun bytes() = byteArrayOf(type.toByte(), (value.size + 2).toByte()) + value
    }

    private fun parseOptions(data: ByteArray): List<Opt> {
        val out = mutableListOf<Opt>()
        var i = 0
        while (i + 2 <= data.size) {
            val t = data[i].toInt() and 0xFF
            val l = data[i + 1].toInt() and 0xFF
            if (l < 2 || i + l > data.size) break
            out += Opt(t, data.copyOfRange(i + 2, i + l))
            i += l
        }
        return out
    }

    private fun optsBytes(opts: List<Opt>) = opts.fold(ByteArray(0)) { acc, o -> acc + o.bytes() }

    private var nextId = 1
    private fun id() = (nextId++ and 0xFF)

    // состояние согласования
    private var nonce = ByteArray(32)
    private var hashProtocol = 2 // 1 = SHA1, 2 = SHA256
    private var auth = AUTH_NONE
    private var hlak = ByteArray(32)

    private fun negotiate(deadline: Long): Negotiated {
        // Call Connect Request: Encapsulated Protocol = PPP
        writePacket(true, control(MSG_CALL_CONNECT_REQUEST, byteArrayOf(0, 1, 0, 6, 0, 1)))

        var lcpOurs = false
        var lcpTheirs = false
        var lcpReqId = -1
        var ourMru: Int? = mtu
        var authStarted = false
        var authDone = false
        var papId = -1
        var ipcpStarted = false
        var ipcpOurs = false
        var ipcpTheirs = false
        var ipcpReqId = -1
        var myIp = ByteArray(4)
        var dns1: ByteArray? = ByteArray(4)
        var dns2: ByteArray? = ByteArray(4)
        var mschap: Mschap? = null

        fun sendLcpRequest() {
            lcpReqId = id()
            val opts = listOfNotNull(ourMru?.let { Opt(1, byteArrayOf((it shr 8).toByte(), it.toByte())) })
            sendPpp(PPP_LCP, cp(CP_CONF_REQ, lcpReqId, optsBytes(opts)))
        }

        fun sendIpcpRequest() {
            ipcpReqId = id()
            val opts = listOfNotNull(
                Opt(3, myIp),
                dns1?.let { Opt(129, it) },
                dns2?.let { Opt(131, it) },
            )
            sendPpp(PPP_IPCP, cp(CP_CONF_REQ, ipcpReqId, optsBytes(opts)))
        }

        fun startAuth() {
            if (authStarted || !lcpOurs || !lcpTheirs) return
            authStarted = true
            when (auth) {
                AUTH_PAP -> {
                    log("PPP: вход PAP")
                    papId = id()
                    val u = user.toByteArray()
                    val pw = password.toByteArray()
                    sendPpp(PPP_PAP, cp(1, papId, byteArrayOf(u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw))
                }
                AUTH_MSCHAP -> log("PPP: вход MS-CHAPv2, жду запрос сервера")
                else -> {
                    // сервер не просил аутентификацию
                    authDone = true
                }
            }
        }

        fun onAuthDone() {
            authDone = true
            log("PPP: вход выполнен")
            sendCallConnected()
            ipcpStarted = true
            sendIpcpRequest()
        }

        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw IOException(
                    when {
                        !lcpOurs || !lcpTheirs -> "нет ответа на этапе LCP"
                        !authDone -> "нет ответа на этапе входа"
                        else -> "сервер не выдал IP-адрес (IPCP)"
                    }
                )
            }
            val pkt = try {
                readPacket()
            } catch (e: java.net.SocketTimeoutException) {
                continue
            }

            if (pkt.control) {
                val type = ((pkt.body[0].toInt() and 0xFF) shl 8) or (pkt.body[1].toInt() and 0xFF)
                when (type) {
                    MSG_CALL_CONNECT_ACK -> {
                        // атрибут Crypto Binding Request: 4 байта заголовка, 3 резерв, 1 битовая маска, 32 nonce
                        val a = pkt.body
                        if (a.size >= 4 + 4 + 36) {
                            val mask = a[4 + 4 + 3].toInt() and 0xFF
                            hashProtocol = if (mask and 2 != 0) 2 else 1
                            nonce = a.copyOfRange(4 + 4 + 4, 4 + 4 + 4 + 32)
                        }
                        log("SSTP: сервер принял вызов, согласование PPP…")
                        sendLcpRequest()
                    }
                    MSG_CALL_CONNECT_NAK -> throw IOException("сервер отклонил вызов SSTP")
                    MSG_CALL_ABORT, MSG_CALL_DISCONNECT -> throw IOException("сервер прервал вызов SSTP")
                    MSG_ECHO_REQUEST -> writePacket(true, control(MSG_ECHO_RESPONSE))
                }
                continue
            }

            val (proto, payload) = parsePpp(pkt.body) ?: continue
            when (proto) {
                PPP_LCP -> {
                    val f = parseCp(proto, payload) ?: continue
                    when (f.code) {
                        CP_CONF_REQ -> {
                            val opts = parseOptions(f.data)
                            val reject = opts.filter { it.type == 7 || it.type == 8 } // сжатие полей — не нужно
                            val authOpt = opts.firstOrNull { it.type == 3 }
                            when {
                                reject.isNotEmpty() -> sendPpp(PPP_LCP, cp(CP_CONF_REJ, f.id, optsBytes(reject)))
                                authOpt != null && !authAcceptable(authOpt) -> {
                                    // предлагаем MS-CHAPv2
                                    val nak = Opt(3, byteArrayOf(0xC2.toByte(), 0x23, 0x81.toByte()))
                                    sendPpp(PPP_LCP, cp(CP_CONF_NAK, f.id, nak.bytes()))
                                }
                                else -> {
                                    auth = when {
                                        authOpt == null -> AUTH_NONE
                                        authOpt.value[0] == 0xC0.toByte() -> AUTH_PAP
                                        else -> AUTH_MSCHAP
                                    }
                                    sendPpp(PPP_LCP, cp(CP_CONF_ACK, f.id, f.data))
                                    lcpTheirs = true
                                    startAuth()
                                }
                            }
                        }
                        CP_CONF_ACK -> if (f.id == lcpReqId) {
                            lcpOurs = true
                            startAuth()
                        }
                        CP_CONF_NAK -> {
                            parseOptions(f.data).firstOrNull { it.type == 1 }?.let {
                                ourMru = ((it.value[0].toInt() and 0xFF) shl 8) or (it.value[1].toInt() and 0xFF)
                            }
                            sendLcpRequest()
                        }
                        CP_CONF_REJ -> {
                            ourMru = null
                            sendLcpRequest()
                        }
                        CP_TERM_REQ -> {
                            sendPpp(PPP_LCP, cp(CP_TERM_ACK, f.id, ByteArray(0)))
                            throw IOException("сервер завершил PPP")
                        }
                        CP_ECHO_REQ -> sendPpp(PPP_LCP, cp(CP_ECHO_REPLY, f.id, ByteArray(4) + f.data.drop(4)))
                    }
                    if (authDone && !ipcpStarted) {
                        ipcpStarted = true
                        sendCallConnected()
                        sendIpcpRequest()
                    }
                }

                PPP_PAP -> {
                    val f = parseCp(proto, payload) ?: continue
                    when (f.code) {
                        2 -> onAuthDone()
                        3 -> throw IOException("сервер отклонил логин vpn/vpn (PAP)")
                    }
                }

                PPP_CHAP -> {
                    val f = parseCp(proto, payload) ?: continue
                    when (f.code) {
                        1 -> { // Challenge
                            val size = f.data[0].toInt() and 0xFF
                            val challenge = f.data.copyOfRange(1, 1 + size)
                            val m = Mschap(user, password, challenge).also { mschap = it }
                            val value = m.peerChallenge + ByteArray(8) + m.ntResponse + byteArrayOf(0)
                            val name = user.toByteArray()
                            sendPpp(PPP_CHAP, cp(2, f.id, byteArrayOf(value.size.toByte()) + value + name))
                        }
                        3 -> { // Success
                            hlak = mschap?.hlak() ?: ByteArray(32)
                            onAuthDone()
                        }
                        4 -> throw IOException("сервер отклонил логин vpn/vpn (MS-CHAPv2)")
                    }
                }

                PPP_IPCP -> {
                    val f = parseCp(proto, payload) ?: continue
                    when (f.code) {
                        CP_CONF_REQ -> {
                            sendPpp(PPP_IPCP, cp(CP_CONF_ACK, f.id, f.data))
                            ipcpTheirs = true
                        }
                        CP_CONF_ACK -> if (f.id == ipcpReqId) ipcpOurs = true
                        CP_CONF_NAK -> {
                            parseOptions(f.data).forEach {
                                when (it.type) {
                                    3 -> myIp = it.value.copyOf(4)
                                    129 -> dns1 = it.value.copyOf(4)
                                    131 -> dns2 = it.value.copyOf(4)
                                }
                            }
                            sendIpcpRequest()
                        }
                        CP_CONF_REJ -> {
                            parseOptions(f.data).forEach {
                                when (it.type) {
                                    129 -> dns1 = null
                                    131 -> dns2 = null
                                }
                            }
                            sendIpcpRequest()
                        }
                        CP_TERM_REQ -> {
                            sendPpp(PPP_IPCP, cp(CP_TERM_ACK, f.id, ByteArray(0)))
                            throw IOException("сервер закрыл IPCP")
                        }
                    }
                    if (ipcpOurs && ipcpTheirs && myIp.any { it != 0.toByte() }) {
                        val ip = InetAddress.getByAddress(myIp) as Inet4Address
                        val dns = listOfNotNull(dns1, dns2)
                            .filter { d -> d.any { it != 0.toByte() } }
                            .map { InetAddress.getByAddress(it) as Inet4Address }
                        log("PPP: IP ${ip.hostAddress}, DNS ${dns.joinToString { it.hostAddress }.ifEmpty { "—" }}")
                        ssl.soTimeout = 0
                        return Negotiated(ip, dns)
                    }
                }

                PPP_IP -> {} // до конца согласования пропускаем

                else -> {
                    // IPv6CP, CCP и прочее — вежливо отказываемся
                    sendPpp(PPP_LCP, cp(CP_PROTO_REJ, id(), byteArrayOf((proto shr 8).toByte(), proto.toByte()) + payload))
                }
            }
        }
    }

    private fun authAcceptable(o: Opt): Boolean {
        val v = o.value
        if (v.size >= 2 && v[0] == 0xC0.toByte() && v[1] == 0x23.toByte()) return true // PAP
        return v.size >= 3 && v[0] == 0xC2.toByte() && v[1] == 0x23.toByte() && v[2] == 0x81.toByte() // MS-CHAPv2
    }

    /** SSTP Call Connected с криптопривязкой (как в Open SSTP Client). */
    private var callConnectedSent = false

    private fun sendCallConnected() {
        if (callConnectedSent) return
        callConnectedSent = true
        val sha256 = hashProtocol == 2
        val digest = if (sha256) "SHA-256" else "SHA-1"
        val macAlg = if (sha256) "HmacSHA256" else "HmacSHA1"
        val macSize = if (sha256) 32 else 20

        val certHash = ByteArray(32)
        runCatching {
            val cert = ssl.session.peerCertificates.first().encoded
            MessageDigest.getInstance(digest).digest(cert).copyInto(certHash)
        }

        fun build(mac: ByteArray): ByteArray {
            val attr = ByteBuffer.allocate(104).apply {
                put(0); put(3); putShort(104)  // заголовок атрибута Crypto Binding
                put(0); put(0); put(0); put(hashProtocol.toByte())
                put(nonce)
                put(certHash)
                put(mac.copyOf(32))
            }.array()
            return control(MSG_CALL_CONNECTED, attr)
        }

        // MAC считается по всему пакету (с заголовком SSTP) с нулевым полем MAC
        val zero = build(ByteArray(32))
        val whole = byteArrayOf(0x10, 1, 0, (zero.size + 4).toByte()) + zero
        val seed = ByteBuffer.allocate(32).apply {
            put("SSTP inner method derived CMK".toByteArray(Charsets.US_ASCII))
            putShort(macSize.toShort())
            put(1)
        }.array()
        val mac = Mac.getInstance(macAlg).run {
            init(SecretKeySpec(hlak, macAlg))
            val cmk = doFinal(seed)
            init(SecretKeySpec(cmk, macAlg))
            doFinal(whole)
        }
        writePacket(true, build(mac))
    }

    // ------------------------------------------------------------------ после подключения

    /**
     * Читает туннель до разрыва: IPv4-пакеты отдаёт в [onIp], на эхо отвечает сам.
     * Возвращает причину разрыва.
     */
    fun pump(onIp: (ByteArray) -> Unit): String {
        try {
            while (!closed) {
                val pkt = readPacket()
                if (pkt.control) {
                    val type = ((pkt.body[0].toInt() and 0xFF) shl 8) or (pkt.body[1].toInt() and 0xFF)
                    when (type) {
                        MSG_ECHO_REQUEST -> writePacket(true, control(MSG_ECHO_RESPONSE))
                        MSG_CALL_DISCONNECT -> {
                            runCatching { writePacket(true, control(MSG_CALL_DISCONNECT_ACK)) }
                            return "сервер закрыл соединение"
                        }
                        MSG_CALL_ABORT -> return "сервер прервал соединение"
                    }
                    continue
                }
                val (proto, payload) = parsePpp(pkt.body) ?: continue
                when (proto) {
                    PPP_IP -> onIp(payload)
                    PPP_LCP -> {
                        val f = parseCp(proto, payload) ?: continue
                        when (f.code) {
                            CP_ECHO_REQ -> sendPpp(PPP_LCP, cp(CP_ECHO_REPLY, f.id, ByteArray(4) + f.data.drop(4)))
                            CP_TERM_REQ -> {
                                runCatching { sendPpp(PPP_LCP, cp(CP_TERM_ACK, f.id, ByteArray(0))) }
                                return "сервер завершил сеанс"
                            }
                        }
                    }
                    PPP_IPCP, PPP_CHAP, PPP_PAP -> {}
                    else -> runCatching {
                        sendPpp(PPP_LCP, cp(CP_PROTO_REJ, id(), byteArrayOf((proto shr 8).toByte(), proto.toByte()) + payload))
                    }
                }
            }
            return "отключено"
        } catch (e: Exception) {
            return if (closed) "отключено" else "обрыв: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Проверка «жив ли сервер» — SSTP Echo. */
    fun sendEcho() = runCatching { writePacket(true, control(MSG_ECHO_REQUEST)) }.isSuccess

    fun close() {
        if (closed) return
        closed = true
        runCatching {
            if (::ssl.isInitialized) {
                runCatching { writePacket(true, control(MSG_CALL_DISCONNECT)) }
                ssl.close()
            }
        }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val MSG_CALL_CONNECT_REQUEST = 1
        const val MSG_CALL_CONNECT_ACK = 2
        const val MSG_CALL_CONNECT_NAK = 3
        const val MSG_CALL_CONNECTED = 4
        const val MSG_CALL_ABORT = 5
        const val MSG_CALL_DISCONNECT = 6
        const val MSG_CALL_DISCONNECT_ACK = 7
        const val MSG_ECHO_REQUEST = 8
        const val MSG_ECHO_RESPONSE = 9

        const val PPP_IP = 0x0021
        const val PPP_LCP = 0xC021
        const val PPP_PAP = 0xC023
        const val PPP_CHAP = 0xC223
        const val PPP_IPCP = 0x8021

        const val CP_CONF_REQ = 1
        const val CP_CONF_ACK = 2
        const val CP_CONF_NAK = 3
        const val CP_CONF_REJ = 4
        const val CP_TERM_REQ = 5
        const val CP_TERM_ACK = 6
        const val CP_PROTO_REJ = 8
        const val CP_ECHO_REQ = 9
        const val CP_ECHO_REPLY = 10

        private const val AUTH_NONE = 0
        private const val AUTH_PAP = 1
        private const val AUTH_MSCHAP = 2
    }
}

/** MS-CHAPv2 (RFC 2759) и ключи MPPE (RFC 3079) для криптопривязки SSTP. */
class Mschap(
    user: String,
    password: String,
    private val authChallenge: ByteArray,
    val peerChallenge: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
) {
    private val pass = password.toByteArray(Charsets.UTF_16LE)
    private val userBytes = user.toByteArray(Charsets.US_ASCII)
    val ntResponse: ByteArray = run {
        val challenge = MessageDigest.getInstance("SHA-1").run {
            update(peerChallenge); update(authChallenge); update(userBytes)
            digest().copyOf(8)
        }
        val z = Md4.hash(pass).copyOf(21)
        val out = ByteArray(24)
        val des = Cipher.getInstance("DES/ECB/NoPadding")
        repeat(3) { i ->
            des.init(Cipher.ENCRYPT_MODE, SecretKeySpec(desKey(z.copyOfRange(i * 7, i * 7 + 7)), "DES"))
            des.doFinal(challenge).copyInto(out, i * 8)
        }
        out
    }

    fun hlak(): ByteArray {
        val magic1 = "This is the MPPE Master Key".toByteArray(Charsets.US_ASCII)
        val magic2 = ("On the client side, this is the send key; on the server side, it is the receive key.")
            .toByteArray(Charsets.US_ASCII)
        val magic3 = ("On the client side, this is the receive key; on the server side, it is the send key.")
            .toByteArray(Charsets.US_ASCII)
        val pad1 = ByteArray(40)
        val pad2 = ByteArray(40) { 0xF2.toByte() }
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(Md4.hash(Md4.hash(pass))); sha.update(ntResponse); sha.update(magic1)
        val master = sha.digest().copyOf(16)
        val out = ByteArray(32)
        sha.reset(); sha.update(master); sha.update(pad1); sha.update(magic2); sha.update(pad2)
        sha.digest().copyInto(out, 0, 0, 16)
        sha.reset(); sha.update(master); sha.update(pad1); sha.update(magic3); sha.update(pad2)
        sha.digest().copyInto(out, 16, 0, 16)
        return out
    }

    /** 7 байт → 8-байтовый ключ DES (бит чётности не важен для JCE). */
    private fun desKey(k: ByteArray): ByteArray {
        val b = k.map { it.toInt() and 0xFF }
        return byteArrayOf(
            (b[0]).toByte(),
            ((b[0] shl 7) or (b[1] shr 1)).toByte(),
            ((b[1] shl 6) or (b[2] shr 2)).toByte(),
            ((b[2] shl 5) or (b[3] shr 3)).toByte(),
            ((b[3] shl 4) or (b[4] shr 4)).toByte(),
            ((b[4] shl 3) or (b[5] shr 5)).toByte(),
            ((b[5] shl 2) or (b[6] shr 6)).toByte(),
            (b[6] shl 1).toByte(),
        ).map { (it.toInt() and 0xFE).toByte() }.toByteArray()
    }
}

/** MD4 (RFC 1320) — в стандартной Java недоступен. */
object Md4 {
    fun hash(input: ByteArray): ByteArray {
        val msgLen = input.size
        val padLen = ((56 - (msgLen + 1) % 64) + 64) % 64
        val buf = ByteBuffer.allocate(msgLen + 1 + padLen + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(input); buf.put(0x80.toByte())
        buf.position(msgLen + 1 + padLen); buf.putLong(msgLen.toLong() * 8)
        buf.flip()
        var a = 0x67452301; var b = 0xefcdab89.toInt(); var c = 0x98badcfe.toInt(); var d = 0x10325476
        val x = IntArray(16)
        fun rl(v: Int, s: Int) = (v shl s) or (v ushr (32 - s))
        while (buf.remaining() >= 64) {
            for (i in 0 until 16) x[i] = buf.int
            val aa = a; val bb = b; val cc = c; val dd = d
            fun f(x1: Int, y: Int, z: Int) = (x1 and y) or (x1.inv() and z)
            fun g(x1: Int, y: Int, z: Int) = (x1 and y) or (x1 and z) or (y and z)
            fun h(x1: Int, y: Int, z: Int) = x1 xor y xor z
            for (i in intArrayOf(0, 4, 8, 12)) {
                a = rl(a + f(b, c, d) + x[i], 3); d = rl(d + f(a, b, c) + x[i + 1], 7)
                c = rl(c + f(d, a, b) + x[i + 2], 11); b = rl(b + f(c, d, a) + x[i + 3], 19)
            }
            for (i in 0 until 4) {
                a = rl(a + g(b, c, d) + x[i] + 0x5A827999, 3); d = rl(d + g(a, b, c) + x[i + 4] + 0x5A827999, 5)
                c = rl(c + g(d, a, b) + x[i + 8] + 0x5A827999, 9); b = rl(b + g(c, d, a) + x[i + 12] + 0x5A827999, 13)
            }
            for (i in intArrayOf(0, 2, 1, 3)) {
                a = rl(a + h(b, c, d) + x[i] + 0x6ED9EBA1, 3); d = rl(d + h(a, b, c) + x[i + 8] + 0x6ED9EBA1, 9)
                c = rl(c + h(d, a, b) + x[i + 4] + 0x6ED9EBA1, 11); b = rl(b + h(c, d, a) + x[i + 12] + 0x6ED9EBA1, 15)
            }
            a += aa; b += bb; c += cc; d += dd
        }
        return ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(a).putInt(b).putInt(c).putInt(d).array()
    }
}
