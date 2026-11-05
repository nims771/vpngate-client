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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.Image
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import kotlin.system.exitProcess

val APP_VERSION: String = System.getProperty("jpackage.app-version") ?: "3.5.3"

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
        settingsStore = DesktopSettingsStore(dir, defaultProtocol = protocols.first()),
        favoritesStore = FavoritesStore(dir),
        prefs = DesktopKeyValueStore(dir, "ui"),
        vpn = backend,
        protocols = protocols,
    )
}

private var instanceLock: FileLock? = null

fun main() {
    val dir = DesktopPaths.appDir
    // второй экземпляр не запускаем: два клиента будут мешать друг другу
    instanceLock = runCatching { RandomAccessFile(File(dir, ".lock"), "rw").channel.tryLock() }.getOrNull()
    if (instanceLock == null) {
        javax.swing.JOptionPane.showMessageDialog(
            null, "VPN Gate Client уже запущен (значок в области уведомлений).", "VPN Gate Client",
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
                tooltip = "VPN Gate Client — ${status.title}",
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
            title = "VPN Gate Client" + if (status.isConnected) " — ${status.title}" else "",
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
