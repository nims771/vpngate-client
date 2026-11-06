package com.example.vpngateclient.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.example.vpngateclient.data.AppSettings
import com.example.vpngateclient.data.Selection
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.ui.CountriesScreen
import com.example.vpngateclient.ui.HomeScreen
import com.example.vpngateclient.ui.LocalFlagFont
import com.example.vpngateclient.ui.SettingsScreen
import com.example.vpngateclient.ui.UiState
import com.example.vpngateclient.ui.theme.AppTheme
import com.example.vpngateclient.vpn.Protocol
import com.example.vpngateclient.vpn.VpnStatus
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Рисует экраны Windows-версии в PNG (build/screenshots). */
class DesktopScreenshotTest {

    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun server(host: String, country: String, code: String, speed: Long, sessions: Int, port: Int) =
        VpnServer(
            hostName = host, ip = "10.0.0.${host.length}", score = speed / 1000, speedBps = speed,
            countryLong = country, countryShort = code, sessions = sessions, uptimeMs = 86_400_000,
            totalUsers = 1000, totalTraffic = 10_000_000, logType = "2weeks", operator = "", message = "",
            proto = "tcp", port = port, config = "",
        )

    private val servers = listOf(
        server("vpn458682530", "Japan", "JP", 869_000_000, 15, 1253),
        server("vpn831105538", "Japan", "JP", 664_000_000, 12, 1329),
        server("vpn557600897", "United States", "US", 74_700_000, 0, 1543),
        server("vpn123456789", "Korea Republic of", "KR", 120_000_000, 7, 995),
        server("vpn222333444", "Thailand", "TH", 45_000_000, 3, 1194),
        server("vpn777888999", "Russian Federation", "RU", 88_000_000, 4, 443),
    )

    private val baseState = UiState(
        servers = servers,
        updatedAt = System.currentTimeMillis() - 5 * 60_000,
        pings = mapOf(
            "vpn458682530" to 207L, "vpn831105538" to 218L, "vpn557600897" to 201L,
            "vpn123456789" to 312L, "vpn222333444" to -1L, "vpn777888999" to 95L,
        ),
        favorites = mapOf(servers[0].id to servers[0]),
        settings = AppSettings(protocol = Protocol.SOFTETHER),
        selection = Selection.Country("JP"),
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        val flagFont = loadFlagFont()
        val scene = ImageComposeScene(width = 440 * 2, height = 860 * 2, density = Density(2f)) {
            CompositionLocalProvider(LocalFlagFont provides flagFont) {
                AppTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        try {
            scene.render(0)
            val img = scene.render(500_000_000)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(outDir, "win_$name.png").writeBytes(png)
        } finally {
            scene.close()
        }
    }

    @Test
    fun home() = shot("1_home_connected") {
        HomeScreen(
            state = baseState.copy(
                connectedSince = System.currentTimeMillis() - 6_000,
                speeds = mapOf("vpn458682530" to 14.2),
                tunnelLatency = 262L,
            ),
            status = VpnStatus(
                level = "LEVEL_CONNECTED", serverId = "vpn458682530",
                serverName = "Japan · vpn458682530", protocol = "SoftEther",
                steps = listOf("Сервер", "Сеанс VPN", "Интернет"), step = 3,
                internetOk = true, publicIp = "219.100.37.12",
            ),
            lastLog = null,
            findServer = { id -> servers.firstOrNull { it.id == id } },
            onToggle = {}, onOpenLocations = {}, onRefresh = {},
        )
    }

    @Test
    fun homeConnecting() = shot("4_home_connecting") {
        HomeScreen(
            state = baseState.copy(connecting = true, attemptInfo = "Сервер 2 из 5: Japan"),
            status = VpnStatus(
                level = "LEVEL_CONNECTING_SERVER_REPLIED", serverId = "vpn831105538",
                serverName = "Japan · vpn831105538", protocol = "SoftEther",
                steps = listOf("Сервер", "Сеанс VPN", "Интернет"), step = 2,
            ),
            lastLog = "13:21:04 Проверка интернета…",
            findServer = { id -> servers.firstOrNull { it.id == id } },
            onToggle = {}, onOpenLocations = {}, onRefresh = {},
        )
    }

    @Test
    fun homeFailed() = shot("5_home_failed") {
        HomeScreen(
            state = baseState,
            status = VpnStatus(
                level = "LEVEL_NOTCONNECTED", serverId = "vpn831105538",
                serverName = "Japan · vpn831105538", protocol = "SoftEther",
                message = "Подключились, но интернета через этот сервер нет",
                steps = listOf("Сервер", "Сеанс VPN", "Интернет"), step = 2, stepFailed = true,
            ),
            lastLog = null,
            findServer = { id -> servers.firstOrNull { it.id == id } },
            onToggle = {}, onOpenLocations = {}, onRefresh = {},
        )
    }

    @Test
    fun updates() = shot("6_updates") {
        val info = com.example.vpngateclient.data.UpdateInfo(
            "4.0.1", "Новое название — ProXvpn. Обновления теперь приходят прямо в приложение.", "", 61_000_000, "github.com",
        )
        androidx.compose.foundation.layout.Column(
            Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            com.example.vpngateclient.ui.UpdateCard(
                com.example.vpngateclient.data.UpdateState.Available(info, prompt = false), "4.0.0", {}, {},
            )
            com.example.vpngateclient.ui.UpdateCard(
                com.example.vpngateclient.data.UpdateState.Downloading(info, 0.42f), "4.0.0", {}, {},
            )
            com.example.vpngateclient.ui.UpdateCard(com.example.vpngateclient.data.UpdateState.UpToDate, "4.0.0", {}, {})
        }
        com.example.vpngateclient.ui.UpdateDialog(
            com.example.vpngateclient.data.UpdateState.Available(info, prompt = true), {}, {},
        )
    }

    @Test
    fun countries() = shot("2_countries") {
        CountriesScreen(
            state = baseState,
            connectedServerId = null,
            onSelect = {}, onRefresh = {}, onPingAll = {}, onStopPing = {}, onQuery = {},
            onServerInfo = {}, onFavorite = {},
        )
    }

    @Test
    fun settings() = shot("3_settings") {
        SettingsScreen(
            settings = baseState.settings,
            versionName = APP_VERSION,
            onChange = {},
            onOpenLog = {},
            protocols = listOf(Protocol.SSTP, Protocol.SOFTETHER, Protocol.WIN_SSTP),
            showSstpTuning = false,
            updates = {
                com.example.vpngateclient.ui.UpdateCard(
                    com.example.vpngateclient.data.UpdateState.Downloading(
                        com.example.vpngateclient.data.UpdateInfo("4.0.1", "", "", 61_000_000, "github.com"), 0.42f,
                    ),
                    "4.0.0", {}, {},
                )
            },
        )
    }

    @Test
    fun csvParsing() {
        val rows = WindowsVpnBackend.csvRows(
            "\"Item\",\"Value\"\n\"VPN Connection Setting Name\",\"VPNGateClient\"\n\"Session Status\",\"Connection Completed (Session Established)\"\n"
        )
        assertEquals(3, rows.size)
        assertEquals("Session Status", rows[2][0])
        assertTrue(rows[2][1].contains("Established"))
    }

    @Test
    fun flagFontLoads() {
        assertTrue(loadFlagFont() != null, "шрифт флагов не найден в ресурсах")
    }
}
