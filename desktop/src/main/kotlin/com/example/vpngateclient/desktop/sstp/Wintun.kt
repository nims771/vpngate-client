package com.example.vpngateclient.desktop.sstp

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.io.IOException

/**
 * Виртуальный сетевой адаптер Wintun (https://www.wintun.net, драйвер от WireGuard).
 * Через него Windows-версия гонит трафик в собственный SSTP-клиент — так же, как Android-версия через VpnService.
 * Нужны права администратора.
 */
class Wintun private constructor(private val lib: Lib, private val adapter: Pointer, private val created: Boolean) {

    @Suppress("FunctionName")
    interface Lib : Library {
        fun WintunCreateAdapter(name: WString, tunnelType: WString, guid: Pointer?): Pointer?
        fun WintunOpenAdapter(name: WString): Pointer?
        fun WintunCloseAdapter(adapter: Pointer)
        fun WintunGetAdapterLUID(adapter: Pointer, luid: Pointer)
        fun WintunGetRunningDriverVersion(): Int
        fun WintunStartSession(adapter: Pointer, capacity: Int): Pointer?
        fun WintunEndSession(session: Pointer)
        fun WintunGetReadWaitEvent(session: Pointer): Pointer
        fun WintunReceivePacket(session: Pointer, size: IntByReference): Pointer?
        fun WintunReleaseReceivePacket(session: Pointer, packet: Pointer)
        fun WintunAllocateSendPacket(session: Pointer, size: Int): Pointer?
        fun WintunSendPacket(session: Pointer, packet: Pointer)
    }

    @Suppress("FunctionName")
    private interface IpHlp : Library {
        fun ConvertInterfaceLuidToIndex(luid: Pointer, index: IntByReference): Int
    }

    /** Номер интерфейса Windows (для netsh). */
    val ifIndex: Int by lazy {
        val luid = Memory(8)
        lib.WintunGetAdapterLUID(adapter, luid)
        val idx = IntByReference()
        val ip = Native.load("iphlpapi", IpHlp::class.java)
        val rc = ip.ConvertInterfaceLuidToIndex(luid, idx)
        if (rc != 0) throw IOException("не удалось узнать номер адаптера ($rc)")
        idx.value
    }

    @Volatile private var session: Pointer? = null
    private var readEvent: WinNT.HANDLE? = null

    fun start() {
        val s = lib.WintunStartSession(adapter, 0x400000) ?: throw IOException("Wintun: не удалось начать сеанс (${Native.getLastError()})")
        session = s
        readEvent = WinNT.HANDLE(lib.WintunGetReadWaitEvent(s))
    }

    /** Следующий пакет из Windows или null, если за [waitMs] ничего не пришло. */
    fun read(waitMs: Int = 200): ByteArray? {
        val s = session ?: return null
        val size = IntByReference()
        val p = lib.WintunReceivePacket(s, size)
        if (p != null) {
            val data = p.getByteArray(0, size.value)
            lib.WintunReleaseReceivePacket(s, p)
            return data
        }
        readEvent?.let { Kernel32.INSTANCE.WaitForSingleObject(it, waitMs) }
        return null
    }

    fun write(packet: ByteArray) {
        val s = session ?: return
        val p = lib.WintunAllocateSendPacket(s, packet.size) ?: return // кольцо переполнено — пакет теряется, как в сети
        p.write(0, packet, 0, packet.size)
        lib.WintunSendPacket(s, p)
    }

    fun close() {
        session?.let { runCatching { lib.WintunEndSession(it) } }
        session = null
        // созданный нами адаптер при закрытии удаляется вместе с адресами и маршрутами
        runCatching { lib.WintunCloseAdapter(adapter) }
    }

    companion object {
        const val NAME = "ProXvpn"
        private const val TYPE = "ProXvpn"

        /** Постоянный GUID — Windows запоминает сеть и не спрашивает про неё каждый раз. */
        private val GUID = byteArrayOf(
            0x3a, 0x51, 0x7c, 0x2e, 0x0b, 0x4d, 0x4f, 0x61,
            0x9a.toByte(), 0x13, 0x5e, 0x77, 0x21, 0x0c, 0x6b, 0x48,
        )

        /** Путь к wintun.dll: рядом с программой (ресурсы установщика) или в папке разработки. */
        fun findDll(): File? {
            val dirs = listOfNotNull(
                System.getProperty("compose.application.resources.dir"),
                System.getProperty("user.dir"),
                File(System.getProperty("java.home") ?: ".").parent,
            )
            return dirs.map { File(it, "wintun.dll") }.firstOrNull { it.isFile }
        }

        fun open(dll: File): Wintun {
            val lib = Native.load(dll.absolutePath, Lib::class.java)
            // остался от прошлого запуска (например, после сбоя) — используем его
            lib.WintunOpenAdapter(WString(NAME))?.let { return Wintun(lib, it, created = false) }
            val guid = Memory(16).apply { write(0, GUID, 0, 16) }
            val a = lib.WintunCreateAdapter(WString(NAME), WString(TYPE), guid)
                ?: throw IOException(
                    "не удалось создать адаптер Wintun (код ${Native.getLastError()}). Нужны права администратора."
                )
            return Wintun(lib, a, created = true)
        }
    }
}
