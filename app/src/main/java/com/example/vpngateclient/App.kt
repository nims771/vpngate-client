package com.example.vpngateclient

import com.example.vpngateclient.vpn.OpenVpnController
import de.blinkt.openvpn.core.ICSOpenVPNApplication
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * ICSOpenVPNApplication создаёт каналы уведомлений VPN и подписывается
 * на статус туннеля из процесса «:openvpn».
 */
class App : ICSOpenVPNApplication() {
    // Создаётся лениво: в процессе «:openvpn» интерфейс не нужен
    val vpn: OpenVpnController by lazy { OpenVpnController(this) }

    override fun onCreate() {
        installCrashRecorder()
        super.onCreate()
    }

    /** Сохраняет текст необработанной ошибки (из любого процесса), чтобы показать его в журнале. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_FILE).writeText("Поток: ${thread.name}\n$sw")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
