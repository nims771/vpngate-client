package com.example.vpngateclient.desktop

import com.example.vpngateclient.data.UpdateInfo
import com.example.vpngateclient.data.UpdatePlatform
import com.example.vpngateclient.data.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Обновление Windows-версии: скачиваем MSI, закрываем программу, ставим MSI поверх
 * (msiexec /passive — только полоска прогресса) и запускаем программу снова.
 */
class WindowsUpdater(private val onExit: () -> Unit) : UpdatePlatform {
    override val key = "windows"
    override val currentVersion: String = APP_VERSION

    override fun isNewer(entry: JSONObject) = Updates.compare(entry.optString("version", "0"), APP_VERSION) > 0
    override fun versionOf(entry: JSONObject): String = entry.optString("version")

    override suspend fun install(info: UpdateInfo, onProgress: (Float) -> Unit): String? {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return "только для Windows"
        val dir = File(System.getProperty("java.io.tmpdir"), "ProXvpn-update").apply { mkdirs() }
        val msi = File(dir, "ProXvpn-update.msi")
        withContext(Dispatchers.IO) { Updates.download(info.url, msi, info.size, onProgress) }

        val exe = System.getProperty("jpackage.app-path")
        val script = File(dir, "update.cmd")
        script.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("timeout /t 3 /nobreak >nul")
                appendLine("msiexec /i \"${msi.absolutePath}\" /passive /norestart")
                if (exe != null) appendLine("start \"\" \"$exe\"")
            },
            Charsets.UTF_8,
        )
        ProcessBuilder("cmd", "/c", script.absolutePath).start()
        onExit()
        return null
    }
}
