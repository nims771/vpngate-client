package com.example.vpngateclient

import android.app.Application
import com.example.vpngateclient.vpn.OpenVpnController

class App : Application() {
    lateinit var vpn: OpenVpnController
        private set

    override fun onCreate() {
        super.onCreate()
        vpn = OpenVpnController(this)
        vpn.bind()
    }
}
