package com.example.vpngateclient.vpn

/** Разбор и подготовка .ovpn-конфигов VPN Gate. */
object OvpnConfig {

    data class Endpoint(val host: String?, val port: Int, val proto: String)

    private val ML = setOf(RegexOption.MULTILINE)
    private val remoteRe = Regex("""^[ \t]*remote[ \t]+(\S+)[ \t]+(\d+)(?:[ \t]+(\S+))?""", ML)
    private val protoRe = Regex("""^[ \t]*proto[ \t]+(\S+)""", ML)
    private val cipherRe = Regex("""^[ \t]*cipher[ \t]+(\S+)""", ML)
    private val dataCiphersRe = Regex("""^[ \t]*data-ciphers[ \t]""", ML)
    private val authUserPassRe = Regex("""^[ \t]*auth-user-pass[ \t]*$""", ML)

    /** Адрес, порт и протокол из строки `remote` / `proto`. */
    fun endpoint(config: String): Endpoint {
        val remote = remoteRe.find(config)
        val protoRaw = remote?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }
            ?: protoRe.find(config)?.groupValues?.get(1)
            ?: "udp"
        val proto = if (protoRaw.lowercase().startsWith("tcp")) "tcp" else "udp"
        val port = remote?.groupValues?.get(2)?.toIntOrNull() ?: 1194
        return Endpoint(remote?.groupValues?.get(1), port, proto)
    }

    /**
     * Делает конфиг VPN Gate совместимым с OpenVPN 2.6 (в OpenVPN for Android):
     * серверы VPN Gate работают на AES-128-CBC, а новые клиенты без
     * data-ciphers такой шифр не согласуют.
     */
    fun prepare(config: String): String {
        var c = config.replace("\r\n", "\n").replace('\r', '\n')

        // Если сервер просит логин/пароль — у VPN Gate это всегда vpn / vpn
        c = authUserPassRe.replace(c, "<auth-user-pass>\nvpn\nvpn\n</auth-user-pass>")

        val cipher = cipherRe.find(c)?.groupValues?.get(1)
        if (cipher != null && !dataCiphersRe.containsMatchIn(c)) {
            val ciphers = linkedSetOf("AES-256-GCM", "AES-128-GCM", "CHACHA20-POLY1305", cipher.uppercase())
            c = c.trimEnd() +
                "\n\n# Добавлено приложением: совместимость с OpenVPN 2.6+\n" +
                "data-ciphers ${ciphers.joinToString(":")}\n" +
                "data-ciphers-fallback $cipher\n"
        }
        return c
    }
}
