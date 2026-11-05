package com.example.vpngateclient.desktop

import com.example.vpngateclient.data.AppSettings
import com.example.vpngateclient.data.KeyValueStore
import com.example.vpngateclient.data.SettingsPersistence
import com.example.vpngateclient.vpn.Protocol
import java.io.File
import java.util.Properties

/** Папка данных: %APPDATA%\VpnGateClient (или ~/.vpngateclient вне Windows). */
object DesktopPaths {
    val appDir: File by lazy {
        val base = System.getenv("APPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"))
        val name = if (System.getenv("APPDATA") != null) "VpnGateClient" else ".vpngateclient"
        File(base, name).apply { mkdirs() }
    }
}

/** Свойства в файле .properties с атомарной записью. */
private class PropertiesFile(private val file: File) {
    private val props = Properties()

    init {
        runCatching { if (file.exists()) file.inputStream().use { props.load(it) } }
    }

    @Synchronized
    fun get(key: String): String? = props.getProperty(key)

    @Synchronized
    fun putAll(values: Map<String, String>) {
        values.forEach { (k, v) -> props.setProperty(k, v) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, "VPN Gate Client") }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

class DesktopSettingsStore(dir: File, private val defaultProtocol: Protocol) : SettingsPersistence {
    private val p = PropertiesFile(File(dir, "settings.properties"))

    private fun int(key: String, def: Int) = p.get(key)?.toIntOrNull() ?: def
    private fun bool(key: String, def: Boolean) = p.get(key)?.toBooleanStrictOrNull() ?: def

    override fun load() = AppSettings(
        listUrl = p.get("listUrl")?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_URL,
        pingTimeoutMs = int("pingTimeoutMs", 3000),
        pingParallel = int("pingParallel", 12),
        autoPing = bool("autoPing", true),
        autoRefresh = bool("autoRefresh", true),
        protocol = runCatching { Protocol.valueOf(p.get("protocol") ?: defaultProtocol.name) }
            .getOrDefault(defaultProtocol),
        autoConnect = bool("autoConnect", false),
        maxAttempts = int("maxAttempts", 5),
    )

    override fun save(s: AppSettings) {
        p.putAll(
            mapOf(
                "listUrl" to s.listUrl,
                "pingTimeoutMs" to s.pingTimeoutMs.toString(),
                "pingParallel" to s.pingParallel.toString(),
                "autoPing" to s.autoPing.toString(),
                "autoRefresh" to s.autoRefresh.toString(),
                "protocol" to s.protocol.name,
                "autoConnect" to s.autoConnect.toString(),
                "maxAttempts" to s.maxAttempts.toString(),
            )
        )
    }
}

class DesktopKeyValueStore(dir: File, name: String) : KeyValueStore {
    private val p = PropertiesFile(File(dir, "$name.properties"))
    override fun getString(key: String): String? = p.get(key)
    override fun putString(key: String, value: String) = p.putAll(mapOf(key to value))
}
