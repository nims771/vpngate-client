package com.example.vpngateclient.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.vpngateclient.data.FavoritesStore
import com.example.vpngateclient.data.ServerRepository
import com.example.vpngateclient.ui.AppCore
import com.example.vpngateclient.ui.LocalFlagFont
import com.example.vpngateclient.ui.theme.AppTheme
import com.example.vpngateclient.vpn.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.Image
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import kotlin.system.exitProcess

val APP_VERSION: String = System.getProperty("jpackage.app-version") ?: "4.0.1"

/** Шрифт Twemoji с флагами стран (в Windows своих флагов-эмодзи нет). */
fun loadFlagFont(): FontFamily? = runCatching {
    val bytes = WindowsVpnBackend::class.java.getResourceAsStream("/TwemojiCountryFlags.ttf")!!.use { it.readBytes() }
    FontFamily(Font("TwemojiCountryFlags", bytes))
}.getOrNull()

fun createCore(backend: WindowsVpnBackend, dir: File, scope: CoroutineScope): AppCore {
    val protocols = desktopProtocols(backend)
    return AppCore(
        scope = scope,
        repo = ServerRepository(dir),
        settingsStore = desktopSettings(dir),
        favoritesStore = FavoritesStore(dir),
        prefs = DesktopKeyValueStore(dir, "ui"),
        vpn = backend,
        protocols = protocols,
        updater = WindowsUpdater(onExit = {
            backend.shutdown()
            runCatching { instanceLock?.release() }
            exitProcess(0)
        }),
    )
}

/**
 * Настройки Windows-версии. С 3.6 основной протокол — свой SSTP (как на Android);
 * у тех, кто раньше был на SoftEther, один раз переключаем на него.
 */
fun desktopSettings(dir: File): DesktopSettingsStore {
    val store = DesktopSettingsStore(dir, defaultProtocol = Protocol.SSTP)
    val ui = DesktopKeyValueStore(dir, "ui")
    if (ui.getString("proto_v36") == null) {
        val s = store.load()
        if (s.protocol != Protocol.SSTP) store.save(s.copy(protocol = Protocol.SSTP))
        ui.putString("proto_v36", "1")
    }
    return store
}

private var instanceLock: FileLock? = null

/** Перезапуск с правами администратора (кнопка в настройках). */
fun restartAsAdmin(backend: WindowsVpnBackend) {
    backend.shutdown()
    runCatching { instanceLock?.release() }
    if (Elevation.relaunchAsAdmin()) exitProcess(0)
    // отказались в окне UAC — продолжаем работать как есть
    instanceLock = runCatching { RandomAccessFile(File(DesktopPaths.appDir, ".lock"), "rw").channel.tryLock() }.getOrNull()
}

fun main() {
    val dir = DesktopPaths.appDir
    // свой SSTP требует прав администратора — сразу просим их (кроме проверки сборки в CI)
    if (!Elevation.isAdmin && System.getenv("VPNGATE_SMOKE_TEST") == null &&
        desktopSettings(dir).load().protocol == Protocol.SSTP
    ) {
        if (Elevation.relaunchAsAdmin()) exitProcess(0)
    }
    // второй экземпляр не запускаем: два клиента будут мешать друг другу
    instanceLock = runCatching { RandomAccessFile(File(dir, ".lock"), "rw").channel.tryLock() }.getOrNull()
    if (instanceLock == null) {
        javax.swing.JOptionPane.showMessageDialog(
            null, "ProXvpn уже запущен (значок в области уведомлений).", "ProXvpn",
            javax.swing.JOptionPane.INFORMATION_MESSAGE,
        )
        exitProcess(0)
    }

    val backend = WindowsVpnBackend()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val core = createCore(backend, dir, scope)
    val flagFont = loadFlagFont()
    val icon = runCatching {
        val bytes = WindowsVpnBackend::class.java.getResourceAsStream("/icon.png")!!.use { it.readBytes() }
        BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
    }.getOrNull()

    Runtime.getRuntime().addShutdownHook(Thread { runCatching { backend.shutdown() } })

    application {
        var visible by remember { mutableStateOf(true) }
        val status by core.vpnStatus.collectAsState()

        fun quit() {
            backend.shutdown()
            exitApplication()
        }

        if (icon != null) {
            Tray(
                icon = icon,
                tooltip = "ProXvpn — ${status.title}",
                onAction = { visible = true },
                menu = {
                    Item("Открыть", onClick = { visible = true })
                    Item(
                        if (status.isActive) "Отключить" else "Подключить",
                        onClick = { core.toggleConnection() },
                    )
                    Separator()
                    Item("Выход", onClick = ::quit)
                },
            )
        }

        Window(
            onCloseRequest = {
                // закрытие окна при активном VPN — сворачиваем в трей, иначе выходим
                if (status.isActive && icon != null) visible = false else quit()
            },
            visible = visible,
            title = "ProXvpn" + if (status.isConnected) " — ${status.title}" else "",
            icon = icon,
            state = rememberWindowState(size = DpSize(440.dp, 860.dp), position = WindowPosition.PlatformDefault),
        ) {
            window.minimumSize = java.awt.Dimension(380, 600)
            // проверка сборки в CI: окно отрисовалось — выходим с кодом 0
            if (System.getenv("VPNGATE_SMOKE_TEST") != null) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(5_000)
                    exitProcess(0)
                }
            }
            CompositionLocalProvider(LocalFlagFont provides flagFont) {
                AppTheme {
                    DesktopApp(core = core, backend = backend, versionName = APP_VERSION)
                }
            }
        }
    }
}
