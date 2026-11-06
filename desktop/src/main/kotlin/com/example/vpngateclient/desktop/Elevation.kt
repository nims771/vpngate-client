package com.example.vpngateclient.desktop

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinUser

/** Права администратора: нужны своему SSTP (виртуальный адаптер и маршруты). */
object Elevation {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    val isAdmin: Boolean by lazy {
        isWindows && runCatching { Shell32.INSTANCE.IsUserAnAdmin() }.getOrDefault(false)
    }

    /** Запускает программу заново с запросом прав (UAC). true — запрос принят. */
    fun relaunchAsAdmin(args: String = ""): Boolean {
        if (!isWindows) return false
        val exe = System.getProperty("jpackage.app-path") ?: return false
        return runCatching {
            val r = Shell32.INSTANCE.ShellExecute(null, "runas", exe, args, null, WinUser.SW_SHOWNORMAL)
            Pointer.nativeValue(r.pointer) > 32
        }.getOrDefault(false)
    }
}
