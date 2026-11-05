package com.example.vpngateclient.data

import android.content.Context
import com.example.vpngateclient.vpn.Protocol

/** Настройки в SharedPreferences (Android). */
class SettingsStore(context: Context) : SettingsPersistence {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    override fun load() = AppSettings(
        listUrl = sp.getString("listUrl", null)?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_URL,
        pingTimeoutMs = sp.getInt("pingTimeout3", 3000),
        pingParallel = sp.getInt("pingParallel3", 12),
        autoPing = sp.getBoolean("autoPing", true),
        autoRefresh = sp.getBoolean("autoRefresh", true),
        // ключ сменён в 3.1: по умолчанию теперь SSTP (OpenVPN часто блокируется)
        protocol = runCatching { Protocol.valueOf(sp.getString("protocol3", null) ?: "SSTP") }
            .getOrDefault(Protocol.SSTP),
        sstpTrustAll = sp.getBoolean("sstpTrustAll", false),
        sstpSendSni = sp.getBoolean("sstpSendSni", false),
        autoConnect = sp.getBoolean("autoConnect", false),
        maxAttempts = sp.getInt("maxAttempts", 5),
        sstpMtu = sp.getInt("sstpMtu", 1400),
    )

    override fun save(s: AppSettings) {
        sp.edit()
            .putString("listUrl", s.listUrl)
            .putInt("pingTimeout3", s.pingTimeoutMs)
            .putInt("pingParallel3", s.pingParallel)
            .putBoolean("autoPing", s.autoPing)
            .putBoolean("autoRefresh", s.autoRefresh)
            .putString("protocol3", s.protocol.name)
            .putBoolean("sstpTrustAll", s.sstpTrustAll)
            .putBoolean("sstpSendSni", s.sstpSendSni)
            .putBoolean("autoConnect", s.autoConnect)
            .putInt("maxAttempts", s.maxAttempts)
            .putInt("sstpMtu", s.sstpMtu)
            .apply()
    }
}


/** SharedPreferences как [KeyValueStore]. */
class PrefsKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun putString(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }
}
