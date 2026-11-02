package com.example.vpngateclient

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.vpngateclient.data.AppSettings
import com.example.vpngateclient.data.Selection
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.ui.CountriesScreen
import com.example.vpngateclient.ui.HomeScreen
import com.example.vpngateclient.ui.SettingsScreen
import com.example.vpngateclient.ui.UiState
import com.example.vpngateclient.ui.theme.AppTheme
import com.example.vpngateclient.vpn.VpnStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Рисует основные экраны в PNG (./gradlew :app:recordRoborazziDebug). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi-night", application = Application::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

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
        server("vpn935882274", "Japan", "JP", 97_000_000, 2, 1796),
        server("vpn557600897", "United States", "US", 74_700_000, 0, 1543),
        server("vpn123456789", "Korea Republic of", "KR", 120_000_000, 7, 995),
        server("vpn222333444", "Thailand", "TH", 45_000_000, 3, 1194),
    )

    private val baseState = UiState(
        servers = servers,
        updatedAt = System.currentTimeMillis() - 5 * 60_000,
        pings = mapOf(
            "vpn458682530" to 207L, "vpn831105538" to 218L, "vpn935882274" to 235L,
            "vpn557600897" to 201L, "vpn123456789" to 312L, "vpn222333444" to -1L,
        ),
        favorites = mapOf(servers[0].id to servers[0]),
        settings = AppSettings(),
        selection = Selection.Country("JP"),
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun homeConnected() = shot("1_home_connected") {
        HomeScreen(
            state = baseState.copy(connectedSince = System.currentTimeMillis() - 6_000),
            status = VpnStatus(
                level = "LEVEL_CONNECTED", serverId = "vpn458682530",
                serverName = "🇯🇵 Japan · vpn458682530", protocol = "SSTP",
            ),
            lastLog = null,
            findServer = { id -> servers.firstOrNull { it.id == id } },
            onToggle = {}, onOpenLocations = {}, onRefresh = {},
        )
    }

    @Test
    fun homeIdle() = shot("2_home_idle") {
        HomeScreen(
            state = baseState.copy(selection = Selection.Auto),
            status = VpnStatus(),
            lastLog = null,
            findServer = { id -> servers.firstOrNull { it.id == id } },
            onToggle = {}, onOpenLocations = {}, onRefresh = {},
        )
    }

    @Test
    fun homeConnecting() = shot("3_home_connecting") {
        HomeScreen(
            state = baseState.copy(selection = Selection.Auto, connecting = true, attemptInfo = "Сервер 2 из 5: 🇯🇵 Japan"),
            status = VpnStatus(level = "LEVEL_CONNECTING_SERVER_REPLIED", serverName = "🇯🇵 Japan · vpn831105538", protocol = "SSTP"),
            lastLog = "13:21:04  SSTP: TLS установлен, HTTPS-запрос SSTP принят сервером",
            findServer = { id -> servers.firstOrNull { it.id == id } },
            onToggle = {}, onOpenLocations = {}, onRefresh = {},
        )
    }

    @Test
    fun countries() = shot("4_countries") {
        CountriesScreen(
            state = baseState,
            connectedServerId = "vpn458682530",
            onSelect = {}, onRefresh = {}, onPingAll = {}, onStopPing = {}, onQuery = {},
            onServerInfo = {}, onFavorite = {},
        )
    }

    @Test
    fun settings() = shot("5_settings") {
        SettingsScreen(settings = AppSettings(), versionName = "3.1", onChange = {}, onOpenLog = {})
    }
}
