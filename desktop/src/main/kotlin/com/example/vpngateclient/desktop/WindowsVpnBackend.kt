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
import kotlinx.coroutines.delay
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

    private fun setStatus(level: String, message: String = "", server: VpnServer? = null, proto: String? = null) {
        _status.update {
            VpnStatus(
                level = level,
                state = level.removePrefix("LEVEL_"),
                message = message,
                serverId = server?.id ?: it.serverId,
                serverName = server?.let { s -> "${s.countryLong} · ${s.hostName}" } ?: it.serverName,
                protocol = proto ?: it.protocol,
            )
        }
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
        setStatus("LEVEL_START", "Подключение к ${server.hostName}…", server, label)
        addLog("=== ${server.countryLong} · ${server.hostName} (${server.ip}), протокол $label ===")

        job = scope.launch {
            ops.withLock {
                val ok = runCatching {
                    if (proto == Protocol.SOFTETHER) connectSoftEther(server) else connectSstp(server)
                }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addLog("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                }.getOrDefault(false)
                ensureActive()
                if (!ok) {
                    setStatus("LEVEL_NOTCONNECTED", "Не удалось подключиться")
                    return@launch
                }
            }
            ensureActive()
            setStatus("LEVEL_CONNECTED", "Подключено", proto = label)
            addLog("Подключено ✔")
            // следим, не оборвалось ли соединение
            while (isActive) {
                delay(4_000)
                val alive = runCatching {
                    if (proto == Protocol.SOFTETHER) softEtherEstablished() == true else sstpConnected()
                }.getOrDefault(true)
                if (!alive) {
                    addLog("Соединение разорвано")
                    setStatus("LEVEL_NOTCONNECTED", "Соединение разорвано")
                    break
                }
            }
        }
        return ConnectResult.Started
    }

    override fun disconnect() {
        val proto = active ?: return
        val wasActive = _status.value.isActive
        job?.cancel()
        job = null
        active = null
        setStatus("LEVEL_NOTCONNECTED", "Отключено")
        if (wasActive) addLog("Отключение…")
        cleanup = scope.launch {
            ops.withLock {
                runCatching {
                    if (proto == Protocol.SOFTETHER) vpncmd("AccountDisconnect", ACCOUNT)
                    else run(listOf("rasdial", RAS_NAME, "/disconnect"), 20)
                }
            }
        }
    }

    /** Синхронное отключение при выходе из программы. */
    fun shutdown() {
        disconnect()
        runBlocking { withTimeoutOrNull(8_000) { cleanup?.join() } }
    }

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

        // 2. учётная запись подключения
        vpncmd("AccountDisconnect", ACCOUNT)
        if (!createAccount(server.ip, port, nic)) return false
        // окна SoftEther «Connecting to…», ошибки и «IP-адрес получен» не показываем —
        // всё видно в самом приложении и в журнале
        vpncmd("AccountStatusHide", ACCOUNT)
        hideSoftEtherWindows(server.ip, port, nic)

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
                    addLog("Сеанс установлен. Windows получает адрес по DHCP…")
                    delay(2_500)
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

    /**
     * Скрывает все всплывающие окна SoftEther для нашего подключения.
     * В vpncmd есть команда только для окна состояния, поэтому выгружаем настройку в файл,
     * включаем HideStatusWindow и HideNicInfoWindow и загружаем обратно.
     */
    private fun hideSoftEtherWindows(ip: String, port: Int, nic: String) {
        val dir = listOfNotNull(System.getenv("ProgramData"), System.getProperty("java.io.tmpdir"))
            .map { File(it, "VpnGateClient") }
            .firstOrNull { it.mkdirs() || it.isDirectory } ?: return
        val file = File(dir, "account.vpn")
        try {
            file.delete()
            val exp = vpncmd("AccountExport", ACCOUNT, "/SAVEPATH:${file.absolutePath}")
            if (exp.code != 0 || !file.isFile) return
            val bytes = file.readBytes()
            val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            val changed = text
                .replace(Regex("(bool\\s+HideStatusWindow\\s+)false"), "$1true")
                .replace(Regex("(bool\\s+HideNicInfoWindow\\s+)false"), "$1true")
            if (changed == text) return
            file.writeBytes((if (bom) "\uFEFF" else "").toByteArray() + changed.toByteArray(Charsets.UTF_8))
            vpncmd("AccountDelete", ACCOUNT)
            val imp = vpncmd("AccountImport", file.absolutePath)
            if (imp.code != 0) {
                // не получилось — возвращаем обычную настройку, окно SoftEther просто будет видно
                addLog("SoftEther: не удалось скрыть окна (${imp.text.lines().lastOrNull { it.isNotBlank() }.orEmpty()})")
                createAccount(ip, port, nic)
                vpncmd("AccountStatusHide", ACCOUNT)
            }
        } finally {
            file.delete()
        }
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
