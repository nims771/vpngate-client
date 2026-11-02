package kittoku.osc.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kittoku.osc.R
import kittoku.osc.SharedBridge
import kittoku.osc.api.SstpEngine
import kittoku.osc.control.Controller
import kittoku.osc.control.LogWriter
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.resetReconnectionLife
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


internal const val ACTION_VPN_CONNECT = "kittoku.osc.connect"
internal const val ACTION_VPN_DISCONNECT = "kittoku.osc.disconnect"

internal const val NOTIFICATION_ERROR_CHANNEL = "SSTP_ERROR"
internal const val NOTIFICATION_RECONNECT_CHANNEL = "SSTP_RECONNECT"
internal const val NOTIFICATION_DISCONNECT_CHANNEL = "SSTP_STATUS"
internal const val NOTIFICATION_CERTIFICATE_CHANNEL = "SSTP_CERTIFICATE"

internal const val NOTIFICATION_ERROR_ID = 101
internal const val NOTIFICATION_RECONNECT_ID = 102
internal const val NOTIFICATION_DISCONNECT_ID = 103
internal const val NOTIFICATION_CERTIFICATE_ID = 104


/**
 * VPN-служба SSTP из Open SSTP Client (MIT, KOBAYASHI Ittoku).
 * Изменено для VPN Gate Client: убраны плитка быстрых настроек и запись журнала
 * в файл, добавлена передача состояния в [SstpEngine].
 */
internal class SstpVpnService : VpnService() {
    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManagerCompat
    internal lateinit var scope: CoroutineScope

    internal var logWriter: LogWriter? = LogWriter()
    private var controller: Controller? = null

    private var jobReconnect: Job? = null

    private fun setRootState(state: Boolean) {
        setBooleanPrefValue(state, OscPrefKey.ROOT_STATE, prefs)
    }

    override fun onCreate() {
        notificationManager = NotificationManagerCompat.from(this)
        prefs = getSharedPreferences(SstpEngine.PREFS_NAME, Context.MODE_PRIVATE)
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_VPN_CONNECT -> {
                controller?.kill(false, null)

                beForegrounded()
                resetReconnectionLife(prefs)

                logWriter?.write("SSTP: подключение к ${prefs.getString(OscPrefKey.HOME_HOSTNAME.name, "")}:" +
                    "${prefs.getInt(OscPrefKey.SSL_PORT.name, 443)}")
                SstpEngine.setState(SstpEngine.Level.CONNECTING, "Подключение по SSTP…")

                initializeClient()

                setRootState(true)

                START_NOT_STICKY
            }

            else -> {
                // ensure that reconnection has been completely canceled or done
                runBlocking { jobReconnect?.cancelAndJoin() }

                controller?.disconnect()
                controller = null

                close()

                START_NOT_STICKY
            }
        }
    }

    private fun initializeClient() {
        controller = Controller(SharedBridge(this)).also {
            it.launchJobMain()
        }
    }

    internal fun launchJobReconnect() {
        jobReconnect = scope.launch {
            try {
                getIntPrefValue(OscPrefKey.RECONNECTION_LIFE, prefs).also {
                    val life = it - 1
                    setIntPrefValue(life, OscPrefKey.RECONNECTION_LIFE, prefs)

                    val message = "Reconnection will be tried (LIFE = $life)"
                    notifyMessage(message, NOTIFICATION_RECONNECT_ID, NOTIFICATION_RECONNECT_CHANNEL)
                    logWriter?.report(message)
                }

                delay(getIntPrefValue(OscPrefKey.RECONNECTION_INTERVAL, prefs) * 1000L)

                initializeClient()
            } catch (_: CancellationException) { }
            finally {
                cancelNotification(NOTIFICATION_RECONNECT_ID)
            }
        }
    }

    private fun beForegrounded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            arrayOf(
                NOTIFICATION_ERROR_CHANNEL,
                NOTIFICATION_RECONNECT_CHANNEL,
                NOTIFICATION_DISCONNECT_CHANNEL,
                NOTIFICATION_CERTIFICATE_CHANNEL,
            ).map {
                NotificationChannel(it, "SSTP", NotificationManager.IMPORTANCE_LOW)
            }.also {
                notificationManager.createNotificationChannels(it)
            }
        }

        val pendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_DISCONNECT_CHANNEL).also {
            it.priority = NotificationCompat.PRIORITY_DEFAULT
            it.setOngoing(true)
            it.setContentTitle("VPN (SSTP)")
            it.setContentText(SstpEngine.serverName ?: "")
            it.setSmallIcon(R.drawable.ic_baseline_vpn_lock_24)
            it.addAction(R.drawable.ic_baseline_close_24, "Отключить", pendingIntent)
            if (openApp != null) it.setContentIntent(openApp)
        }

        startForeground(NOTIFICATION_DISCONNECT_ID, builder.build())
    }

    internal fun notifyMessage(message: String, id: Int, channel: String) {
        NotificationCompat.Builder(this, channel).also {
            it.setSmallIcon(R.drawable.ic_baseline_vpn_lock_24)
            it.setContentText(message)
            it.priority = NotificationCompat.PRIORITY_DEFAULT
            it.setAutoCancel(true)

            tryNotify(it.build(), id)
        }
    }

    internal fun notifyError(message: String) {
        // Ошибку показываем в приложении, отдельное уведомление не нужно
        SstpEngine.lastError = message
    }

    internal fun tryNotify(notification: Notification, id: Int) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(id, notification)
        }
    }

    internal fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
    }

    internal fun close() {
        SstpEngine.setState(SstpEngine.Level.DISCONNECTED, SstpEngine.lastError ?: "")
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() {
        // Другой VPN занял систему или пользователь отключил нас в настройках
        controller?.disconnect()
        controller = null
        close()
    }

    override fun onDestroy() {
        logWriter?.write("SSTP: служба остановлена")
        logWriter = null

        controller?.kill(false, null)
        controller = null

        scope.cancel()

        setRootState(false)
        SstpEngine.setState(SstpEngine.Level.DISCONNECTED, SstpEngine.lastError ?: "")
    }
}
