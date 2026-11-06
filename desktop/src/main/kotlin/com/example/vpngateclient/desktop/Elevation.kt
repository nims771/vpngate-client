package com.example.vpngateclient.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString

/** Права администратора: нужны своему SSTP (виртуальный адаптер и маршруты). */
object Elevation {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Suppress("FunctionName")
    private interface Shell32 : Library {
        fun IsUserAnAdmin(): Boolean
        fun ShellExecuteW(hwnd: Pointer?, op: WString, file: WString, params: WString?, dir: WString?, show: Int): Pointer?
    }

    private val shell: Shell32? by lazy {
        if (isWindows) runCatching { Native.load("shell32", Shell32::class.java) }.getOrNull() else null
    }

    val isAdmin: Boolean by lazy { runCatching { shell?.IsUserAnAdmin() == true }.getOrDefault(false) }

    /** Запускает программу заново с запросом прав (UAC). true — запрос принят. */
    fun relaunchAsAdmin(args: String = ""): Boolean {
        val s = shell ?: return false
        val exe = System.getProperty("jpackage.app-path") ?: return false
        return runCatching {
            val r = s.ShellExecuteW(null, WString("runas"), WString(exe), WString(args), null, 1 /* SW_SHOWNORMAL */)
            Pointer.nativeValue(r) > 32
        }.getOrDefault(false)
    }
}
