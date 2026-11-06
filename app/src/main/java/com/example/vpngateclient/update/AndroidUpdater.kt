package com.example.vpngateclient.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.vpngateclient.data.UpdateInfo
import com.example.vpngateclient.data.UpdatePlatform
import com.example.vpngateclient.data.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Обновление Android-версии: скачиваем APK и открываем системный установщик. */
class AndroidUpdater(context: Context) : UpdatePlatform {
    private val app = context.applicationContext

    private val info = app.packageManager.getPackageInfo(app.packageName, 0)

    @Suppress("DEPRECATION")
    private val versionCode: Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    override val key = "android"
    override val currentVersion: String = info.versionName ?: versionCode.toString()

    override fun isNewer(entry: JSONObject) = entry.optLong("versionCode", 0) > versionCode
    override fun versionOf(entry: JSONObject): String = entry.optString("versionName", entry.optString("versionCode"))

    override suspend fun install(info: UpdateInfo, onProgress: (Float) -> Unit): String? {
        // Android 8+: пользователь один раз разрешает приложению ставить APK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Разрешите установку из этого источника, вернитесь и нажмите «Обновить» ещё раз"
        }
        val apk = File(app.cacheDir, "updates/ProXvpn.apk")
        withContext(Dispatchers.IO) {
            apk.parentFile?.listFiles()?.forEach { it.delete() }
            Updates.download(info.url, apk, info.size, onProgress)
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return null
    }
}
