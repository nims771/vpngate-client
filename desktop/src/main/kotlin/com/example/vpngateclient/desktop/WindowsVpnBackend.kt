package com.example.vpngateclient.desktop

import com.example.vpngateclient.data.AppSettings
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.vpn.ConnectResult
import com.example.vpngateclient.vpn.Protocol
import com.example.vpngateclient.vpn.VpnBackend
import com.example.vpngateclient.vpn.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * VPN на Windows без собственного драйвера:
 *  - SoftEther — через установленный SoftEther VPN Client (тот же протокол, что у VPN Gate на ПК);
 *  - SSTP — через встроенный в Windows клиент (rasdial), ничего ставить не нужно.
 */
class WindowsVpnBackend : VpnBackend {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(VpnStatus())
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    override val log: StateFlow<List<String>> = _log.asStateFlow()

    private val ops = Mutex()
    private var job: Job? = null
    private var cleanup: Job? = null
    /** Что сейчас поднято — чтобы правильно отключить. */
    @Volatile private var active: Protocol? = null

    val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Путь к vpncmd.exe или null, если SoftEther VPN Client не установлен. */
    val vpncmd: File? by lazy { findVpncmd() }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun addLog(text: String) {
        val stamp = timeFmt.format(Date())
        val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.map { "$stamp $it" }
        if (lines.isNotEmpty()) _log.update { (it + lines).takeLast(MAX_LOG) }
    }

    override fun clearLog() {
        _log.value = emptyList()
    }

    // ------------------------------------------------------------------ статус

    private fun lvl(st: VpnStatus, level: String, message: String) =
        st.copy(level = level, state = level.removePrefix("LEVEL_"), message = message)

    private fun setStep(i: Int, message: String) = _status.update {
        lvl(it, if (i == 0) "LEVEL_START" else "LEVEL_CONNECTING_SERVER_REPLIED", message).copy(step = i)
    }

    private fun failStep(i: Int, message: String) {
        addLog(message)
        _status.update { lvl(it, "LEVEL_NOTCONNECTED", message).copy(step = i, stepFailed = true) }
    }

    // ------------------------------------------------------------------ connect

    override suspend fun connect(server: VpnServer, settings: AppSettings): ConnectResult {
        if (!isWindows) return ConnectResult.Error("Подключение работает только в Windows")
        val proto = if (settings.protocol == Protocol.SOFTETHER) Protocol.SOFTETHER else Protocol.SSTP
        if (proto == Protocol.SOFTETHER && vpncmd == null) {
            return ConnectResult.Error(
                "SoftEther VPN Client не найден. Установите его (Настройки → «Скачать») или выберите протокол SSTP."
            )
        }

        cleanup?.join()
        job?.cancel()
        active = proto
        val label = if (proto == Protocol.SOFTETHER) "SoftEther" else "SSTP"
        // статус «подключение» ставим сразу: AppCore ждёт либо успеха, либо ошибки
        _status.value = lvl(
            VpnStatus(
                serverId = server.id,
                serverName = "${server.countryLong} · ${server.hostName}",
                protocol = label,
                steps = STEPS,
            ),
            "LEVEL_START", "Подключение к ${server.hostName}…",
        )
        addLog("=== ${server.countryLong} · ${server.hostName} (${server.ip}), протокол $label ===")

        job = scope.launch {
            launch { refreshBaseline() } // параллельно с подключением, не задерживает его
            // 1. туннель
            val ok = ops.withLock {
                runCatching {
                    if (proto == Protocol.SOFTETHER) connectSoftEther(server) else connectSstp(server)
                }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addLog("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                }.getOrDefault(false)
            }
            ensureActive()
            if (!ok) {
                failStep(0, "Сервер не принял подключение")
                return@launch
            }

            // 2. сеанс есть — считаем подключённым (как в первой версии: быстро),
            // а IP и интернет проверяем уже в фоне, только для показа на экране
            setStep(1, "Сеанс VPN установлен…")
            delay(2_500) // Windows получает адрес по DHCP
            ensureActive()
            _status.update { lvl(it, "LEVEL_CONNECTED", "Подключено").copy(step = 2) }
            addLog("Подключено ✔")
            launch { checkInternetInBackground(proto) }
            monitor(proto)
        }
        return ConnectResult.Started
    }

    /** Фоновая проверка интернета через VPN — только для индикатора, соединение не рвёт. */
    private suspend fun checkInternetInBackground(proto: Protocol) {
        for (attempt in 1..6) {
            val ip = findVpnIp(proto)
            val r = probeInternet(ip, verbose = attempt == 1 || attempt == 6)
            if (!_status.value.isConnected) return
            if (r != null) {
                _status.update { it.copy(step = STEPS.size, internetOk = true, publicIp = r.ifBlank { null }) }
                addLog("Интернет через VPN работает" + (r.takeIf { it.isNotBlank() }?.let { ", ваш IP: $it" } ?: ""))
                return
            }
            delay(5_000)
        }
        if (_status.value.isConnected) {
            _status.update { it.copy(internetOk = false) }
            addLog("Не удалось подтвердить интернет через VPN. Если сайты не открываются — «Сменить сервер».")
        }
    }

    private fun findVpnIp(proto: Protocol): java.net.InetAddress? =
        findVpnInterface(proto)?.inetAddresses?.toList()
            ?.filterIsInstance<java.net.Inet4Address>()
            ?.firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }

    /** Следим, жив ли сеанс. Оборвался — AppCore переподключит к другому серверу. */
    private suspend fun monitor(proto: Protocol) {
        var deadChecks = 0
        while (currentCoroutineContext().isActive) {
            delay(4_000)
            val alive = runCatching {
                if (proto == Protocol.SOFTETHER) softEtherEstablished() != false else sstpConnected()
            }.getOrDefault(true)
            deadChecks = if (alive) 0 else deadChecks + 1
            if (deadChecks >= 2) {
                drop(proto, "Сервер разорвал соединение")
                return
            }
        }
    }

    private suspend fun drop(proto: Protocol, message: String) {
        addLog(message)
        active = null
        _status.update { lvl(it, "LEVEL_NOTCONNECTED", message).copy(dropped = true, steps = emptyList()) }
        teardown(proto)
    }

    private suspend fun teardown(proto: Protocol) {
        if (active == proto) active = null
        ops.withLock {
            runCatching {
                if (proto == Protocol.SOFTETHER) vpncmd("AccountDisconnect", ACCOUNT)
                else run(listOf("rasdial", RAS_NAME, "/disconnect"), 20)
            }
        }
    }

    override fun disconnect() {
        val proto = active ?: return
        val wasActive = _status.value.isActive
        job?.cancel()
        job = null
        active = null
        // после ошибки статус не трогаем — на экране остаётся, на каком этапе не получилось
        if (wasActive) _status.update {
            VpnStatus(serverId = it.serverId, serverName = it.serverName, protocol = it.protocol, message = "Отключено")
        }
        if (wasActive) addLog("Отключение…")
        cleanup = scope.launch { teardown(proto) }
    }

    /** Синхронное отключение при выходе из программы. */
    fun shutdown() {
        disconnect()
        runBlocking { withTimeoutOrNull(8_000) { cleanup?.join() } }
    }

    // ------------------------------------------------------------------ IP и интернет

    @Volatile private var currentNic = "VPN"

    private fun findVpnInterface(proto: Protocol): java.net.NetworkInterface? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().firstOrNull { nif ->
            val d = nif.displayName.orEmpty()
            if (proto == Protocol.SOFTETHER) {
                d.contains("VPN Client Adapter", true) && d.trim().endsWith("- $currentNic", true)
            } else {
                d.equals(RAS_NAME, true) || (nif.isUp && nif.isPointToPoint && nif.name.startsWith("ppp"))
            }
        }
    }.getOrNull()

    /** Внешний IP без VPN — чтобы понять, что запрос ушёл мимо туннеля. */
    @Volatile private var baselineIp: String? = null
    @Volatile private var baselineAt = 0L

    private suspend fun refreshBaseline() {
        if (System.currentTimeMillis() - baselineAt < 10 * 60_000) return
        baselineAt = System.currentTimeMillis()
        baselineIp = withContext(Dispatchers.IO) { runCatching { trace(null, "1.1.1.1", 4_000) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Есть ли интернет через VPN. Пробуем несколькими способами сразу (с адреса VPN-адаптера и обычным путём),
     * хватает любого. Возвращает внешний IP ("" — интернет есть, IP не узнали) или null.
     */
    private suspend fun probeInternet(local: java.net.InetAddress?, verbose: Boolean = false): String? = coroutineScope {
        val bound = local?.let { l -> async(Dispatchers.IO) { runCatching { trace(l, "1.1.1.1", 5_000) } } }
        val boundDns = local?.let { l -> async(Dispatchers.IO) { runCatching { tcp(l, "8.8.8.8", 53) } } }
        val plain = async(Dispatchers.IO) { runCatching { trace(null, "1.0.0.1", 5_000) } }
        val plainDns = async(Dispatchers.IO) { runCatching { tcp(null, "8.8.4.4", 53) } }
        val ms = async(Dispatchers.IO) { runCatching { msConnectTest() } }

        val rBound = bound?.await()
        val rBoundDns = boundDns?.await()
        val rPlain = plain.await()
        val rPlainDns = plainDns.await()
        val rMs = ms.await()

        if (verbose) {
            fun d(r: Result<*>?) = when {
                r == null -> "—"
                r.isSuccess -> "ok" + (r.getOrNull() as? String)?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                else -> r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }.orEmpty().take(60)
            }
            addLog(
                "Проверка интернета: VPN-адрес ${local?.hostAddress ?: "?"}; " +
                    "1.1.1.1 через VPN: ${d(rBound)}; DNS через VPN: ${d(rBoundDns)}; " +
                    "1.0.0.1: ${d(rPlain)}; DNS: ${d(rPlainDns)}; msft: ${d(rMs)}" +
                    (baselineIp?.let { "; IP без VPN: $it" } ?: "")
            )
        }

        // ответ пришёл с нашим же IP без VPN — значит, запрос ушёл мимо туннеля
        val plainIp = rPlain.getOrNull()?.takeIf { it != baselineIp || it.isBlank() }
        when {
            rBound?.isSuccess == true -> rBound.getOrNull()
            rBoundDns?.isSuccess == true -> plainIp ?: ""
            plainIp != null -> plainIp
            rPlain.isSuccess -> null // ушло мимо VPN
            rPlainDns.isSuccess || rMs.isSuccess -> ""
            else -> null
        }
    }

    /** GET http://<ip>/cdn-cgi/trace — внешний IP ("" если в ответе его нет). */
    private fun trace(local: java.net.InetAddress?, target: String, timeout: Int): String =
        java.net.Socket().use { s ->
            if (local != null) s.bind(java.net.InetSocketAddress(local, 0))
            s.connect(java.net.InetSocketAddress(target, 80), timeout)
            s.soTimeout = timeout
            s.getOutputStream().write(
                "GET /cdn-cgi/trace HTTP/1.1\r\nHost: $target\r\nUser-Agent: VpnGateClient\r\nConnection: close\r\n\r\n"
                    .toByteArray()
            )
            val text = String(s.getInputStream().readNBytes(4096), Charsets.ISO_8859_1)
            if (!text.startsWith("HTTP/")) error("нет HTTP-ответа")
            Regex("(?m)^ip=([0-9a-fA-F.:]+)").find(text)?.groupValues?.get(1) ?: ""
        }

    private fun tcp(local: java.net.InetAddress?, host: String, port: Int): String =
        java.net.Socket().use { s ->
            if (local != null) s.bind(java.net.InetSocketAddress(local, 0))
            s.connect(java.net.InetSocketAddress(host, port), 5_000)
            ""
        }

    /** Проверка Windows: http://www.msftconnecttest.com/connecttest.txt */
    private fun msConnectTest(): String {
        val c = java.net.URL("http://www.msftconnecttest.com/connecttest.txt").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        c.useCaches = false
        try {
            val body = c.inputStream.use { String(it.readNBytes(200)) }
            if (!body.contains("Microsoft Connect Test")) error("ответ подменён")
            return ""
        } finally {
            c.disconnect()
        }
    }

    override fun connectTimeoutMs(protocol: Protocol): Long = 75_000

    // ------------------------------------------------------------------ SoftEther

    private suspend fun connectSoftEther(server: VpnServer): Boolean {
        val port = server.sstpPort
        // 1. виртуальный адаптер
        val nics = vpncmd("NicList")
        if (nics.code != 0) {
            addLog(nics.text)
            addLog("Служба SoftEther VPN Client не отвечает. Запустите «SoftEther VPN Client Manager» или перезагрузите ПК.")
            return false
        }
        var nic = csvRows(nics.text).drop(1).firstOrNull()?.firstOrNull()
        if (nic == null) {
            addLog("Создаю виртуальный адаптер VPN… (может появиться запрос Windows)")
            val r = vpncmd("NicCreate", "VPN")
            if (r.code != 0) {
                addLog(r.text)
                addLog("Не удалось создать адаптер. Создайте его вручную в SoftEther VPN Client Manager.")
                return false
            }
            nic = "VPN"
        }
        // SoftEther показывает адаптер как «VPN Client Adapter - VPN», а принимает имя «VPN»
        nic = nic.substringAfterLast(" - ").trim().ifEmpty { "VPN" }
        currentNic = nic

        // 2. учётная запись подключения
        vpncmd("AccountDisconnect", ACCOUNT)
        if (!createAccount(server.ip, port, nic)) return false
        // окна SoftEther «Connecting to…», ошибки и «IP-адрес получен» не показываем —
        // всё видно в самом приложении и в журнале
        vpncmd("AccountStatusHide", ACCOUNT)

        // 3. подключение
        addLog("Подключение к ${server.ip}:$port (SoftEther, адаптер $nic)…")
        val conn = vpncmd("AccountConnect", ACCOUNT)
        if (conn.code != 0) {
            addLog(conn.text)
            return false
        }
        val start = System.currentTimeMillis()
        var lastState = ""
        while (System.currentTimeMillis() - start < 40_000) {
            delay(1_500)
            val st = vpncmd("AccountStatusGet", ACCOUNT)
            val rows = csvRows(st.text)
            val state = rows.firstOrNull { it.size >= 2 && (it[0].contains("Status", true) || it[0].contains("Статус", true)) }
                ?.getOrNull(1).orEmpty()
            if (state.isNotEmpty() && state != lastState) {
                addLog("SoftEther: $state")
                lastState = state
            }
            when (establishedFrom(st, rows)) {
                true -> {
                    addLog("Сеанс VPN установлен")
                    return true
                }
                false -> if (System.currentTimeMillis() - start > 6_000) {
                    if (st.code != 0) addLog(st.text)
                    addLog("Сервер не принял подключение")
                    return false
                }
                null -> {}
            }
        }
        addLog("Нет ответа за 40 с")
        vpncmd("AccountDisconnect", ACCOUNT)
        return false
    }

    private fun createAccount(ip: String, port: Int, nic: String): Boolean {
        vpncmd("AccountDelete", ACCOUNT)
        val create = vpncmd(
            "AccountCreate", ACCOUNT,
            "/SERVER:$ip:$port", "/HUB:VPNGATE", "/USERNAME:vpn", "/NICNAME:$nic",
        )
        if (create.code != 0) {
            addLog(create.text)
            return false
        }
        vpncmd("AccountAnonymousSet", ACCOUNT)
        vpncmd("AccountRetrySet", ACCOUNT, "/NUM:0", "/INTERVAL:5")
        vpncmd("AccountServerCertDisable", ACCOUNT)
        return true
    }

    /** true — установлен, false — не подключено (ошибка), null — ещё подключается. */
    private fun establishedFrom(st: CmdResult, rows: List<List<String>>): Boolean? {
        if (st.code != 0) return false
        val t = st.text.lowercase()
        if (ESTABLISHED.any { it in t }) return true
        // после установки сеанса vpncmd показывает много строк (шифр, сервер, трафик)
        if (rows.size >= 12) return true
        return null
    }

    private fun softEtherEstablished(): Boolean? {
        val st = vpncmd("AccountStatusGet", ACCOUNT)
        return establishedFrom(st, csvRows(st.text))
    }

    private fun vpncmd(vararg args: String): CmdResult {
        val exe = vpncmd ?: return CmdResult(-1, "vpncmd не найден")
        return run(listOf(exe.absolutePath, "localhost", "/CLIENT", "/CSV", "/CMD") + args, 30)
    }

    private fun findVpncmd(): File? {
        if (!isWindows) return null
        val dirs = listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
            System.getenv("ProgramW6432"),
            "C:\\Program Files",
            "C:\\Program Files (x86)",
        ).distinct().map { File(it, "SoftEther VPN Client") }
        val names = listOf("vpncmd_x64.exe", "vpncmd.exe", "vpncmd_x86.exe")
        dirs.forEach { d -> names.forEach { n -> File(d, n).takeIf { it.isFile }?.let { return it } } }
        // нестандартная папка — по реестру службы
        val reg = run(listOf("reg", "query", "HKLM\\SYSTEM\\CurrentControlSet\\Services\\SEVPNCLIENT", "/v", "ImagePath"), 10)
        Regex("([A-Za-z]:\\\\[^\"]+?)\\\\vpnclient[^\\\\]*\\.exe", RegexOption.IGNORE_CASE).find(reg.text)?.let { m ->
            val d = File(m.groupValues[1])
            names.forEach { n -> File(d, n).takeIf { it.isFile }?.let { return it } }
        }
        return null
    }

    // ------------------------------------------------------------------ Windows SSTP

    private suspend fun connectSstp(server: VpnServer): Boolean {
        // сертификат VPN Gate выдан на имя *.opengw.net, поэтому подключаемся по имени, не по IP
        val host = "${server.hostName}.opengw.net"
        val port = server.sstpPort
        val address = if (port == 443) host else "$host:$port"

        run(listOf("rasdial", RAS_NAME, "/disconnect"), 15)
        val ps = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}n = '$RAS_NAME'
            Get-VpnConnection -Name ${'$'}n -ErrorAction SilentlyContinue | Remove-VpnConnection -Force -ErrorAction SilentlyContinue
            Add-VpnConnection -Name ${'$'}n -ServerAddress '$address' -TunnelType Sstp -AuthenticationMethod MSChapv2 -EncryptionLevel Optional -RememberCredential -Force
            'OK'
        """.trimIndent()
        val add = powershell(ps)
        if (add.code != 0 || !add.text.contains("OK")) {
            addLog(add.text)
            addLog("Не удалось создать подключение Windows")
            return false
        }
        addLog("Подключение к $address (SSTP Windows)…")
        val r = run(listOf("rasdial", RAS_NAME, "vpn", "vpn"), 60)
        if (r.code == 0) return true
        addLog(r.text.ifBlank { "rasdial: код ${r.code}" })
        sstpHint(r)?.let { addLog(it) }
        return false
    }

    private fun sstpHint(r: CmdResult): String? {
        val t = r.text.lowercase() + " " + r.code
        return when {
            "0x80092013" in t || "2148204563" in t || " 4294967283" in t || "-2146762733" in t ->
                "Windows не может проверить отзыв сертификата сервера. Нажмите в настройках «Отключить проверку отзыва для SSTP» и попробуйте снова."
            "800b0109" in t || "-2146762487" in t ->
                "Windows не доверяет сертификату этого сервера — выберите другой сервер или протокол SoftEther."
            "691" in t -> "Сервер отклонил логин vpn/vpn."
            Regex("\\b(800|809)\\b").containsMatchIn(t) -> "Сервер не ответил (возможно, блокировка порта). Попробуйте другой сервер."
            else -> null
        }
    }

    private fun sstpConnected(): Boolean {
        val r = run(listOf("rasdial"), 10)
        return r.text.contains(RAS_NAME, ignoreCase = true)
    }

    /** Отключает проверку отзыва сертификата в службе SSTP (нужны права администратора). */
    fun fixSstpRevocation(): String {
        if (!isWindows) return "Только для Windows"
        val inner = "New-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Services\\SstpSvc\\Parameters' " +
            "-Name NoCertRevocationCheck -PropertyType DWord -Value 1 -Force; Restart-Service SstpSvc -Force"
        val r = powershell(
            "Start-Process powershell -Verb RunAs -WindowStyle Hidden -Wait -ArgumentList " +
                "'-NoProfile','-Command',\"${inner.replace("\"", "`\"")}\""
        )
        val msg = if (r.code == 0) "Готово: проверка отзыва сертификата для SSTP отключена"
        else "Не удалось (нужно подтвердить запрос администратора)"
        addLog(msg)
        if (r.code != 0) addLog(r.text)
        return msg
    }

    // ------------------------------------------------------------------ процессы

    data class CmdResult(val code: Int, val text: String)

    private fun powershell(script: String): CmdResult =
        run(listOf("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script), 60)

    private fun run(cmd: List<String>, timeoutSec: Long): CmdResult = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream
        val bytes = java.io.ByteArrayOutputStream()
        val reader = Thread { runCatching { out.copyTo(bytes) } }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            reader.join(1_000)
            return CmdResult(-2, decode(bytes.toByteArray()) + "\n(нет ответа за $timeoutSec с)")
        }
        reader.join(2_000)
        CmdResult(p.exitValue(), decode(bytes.toByteArray()))
    }.getOrElse { CmdResult(-1, it.message ?: it.toString()) }

    /** Консольные программы Windows пишут в UTF-8 или в кодировке OEM (866 для русской Windows). */
    private fun decode(b: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { utf8.decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrElse {
            val oem = runCatching { Charset.forName("IBM866") }.getOrNull()
            String(b, if (Locale.getDefault().language == "ru" && oem != null) oem else Charset.defaultCharset())
        }.replace("\r", "")
    }

    companion object {
        const val ACCOUNT = "VPNGateClient"
        const val RAS_NAME = "VPN Gate Client (SSTP)"
        private val STEPS = listOf("Сервер", "Сеанс VPN", "Интернет")

        private const val MAX_LOG = 400
        private val ESTABLISHED = listOf("established", "установлен", "確立", "已建立", "연결 완료")

        /** Простой разбор CSV из vpncmd /CSV. */
        fun csvRows(text: String): List<List<String>> = text.lines()
            .filter { it.startsWith("\"") || (it.contains(",") && !it.startsWith("vpncmd")) }
            .map { line ->
                val out = mutableListOf<String>()
                val sb = StringBuilder()
                var q = false
                var i = 0
                while (i < line.length) {
                    val c = line[i]
                    when {
                        c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                        c == '"' -> q = !q
                        c == ',' && !q -> { out += sb.toString(); sb.clear() }
                        else -> sb.append(c)
                    }
                    i++
                }
                out += sb.toString()
                out
            }
    }
}
