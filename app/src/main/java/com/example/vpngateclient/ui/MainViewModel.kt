package com.example.vpngateclient.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vpngateclient.App
import com.example.vpngateclient.data.FavoritesStore
import com.example.vpngateclient.data.PrefsKeyValueStore
import com.example.vpngateclient.data.ServerRepository
import com.example.vpngateclient.data.SettingsStore
import com.example.vpngateclient.data.VpnServer
import com.example.vpngateclient.vpn.Protocol
import com.example.vpngateclient.update.AndroidUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android-обёртка над общей логикой [AppCore]. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val core = AppCore(
        scope = viewModelScope,
        repo = ServerRepository(app.filesDir),
        settingsStore = SettingsStore(app),
        favoritesStore = FavoritesStore(app.filesDir),
        prefs = PrefsKeyValueStore(app, "ui"),
        vpn = (app as App).vpn,
        protocols = listOf(Protocol.AUTO, Protocol.SSTP, Protocol.OPENVPN),
        updater = AndroidUpdater(app),
    )

    var pendingExport: VpnServer? = null

    fun exportTo(uri: Uri) {
        val server = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(core.ovpnText(server).toByteArray())
                    } ?: error("no stream")
                }.isSuccess
            }
            core.notify(if (ok) "Файл .ovpn сохранён" else "Не удалось сохранить файл")
        }
    }
}
