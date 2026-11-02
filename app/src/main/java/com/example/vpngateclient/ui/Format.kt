package com.example.vpngateclient.ui

import androidx.compose.ui.graphics.Color
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")

fun formatSpeed(bps: Long): String =
    if (bps >= 1_000_000) String.format(RU, "%.1f Мбит/с", bps / 1_000_000.0)
    else "${bps / 1000} Кбит/с"

fun formatUptime(ms: Long): String {
    val totalMin = ms / 60_000
    val d = totalMin / 1440
    val h = (totalMin % 1440) / 60
    val m = totalMin % 60
    return when {
        d > 0 -> "$d д $h ч"
        h > 0 -> "$h ч $m мин"
        else -> "$m мин"
    }
}

fun formatBytes(bytes: Long): String {
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ", "ПБ")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024; i++
    }
    return if (i == 0) "$bytes Б" else String.format(RU, "%.1f %s", v, units[i])
}

fun formatNumber(n: Long): String = String.format(RU, "%,d", n)

fun formatAgo(time: Long, now: Long = System.currentTimeMillis()): String {
    if (time <= 0) return "никогда"
    val min = (now - time) / 60_000
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 60 * 24 -> "${min / 60} ч назад"
        else -> "${min / 1440} д назад"
    }
}

fun pingColor(ms: Long): Color = when {
    ms < 100 -> Color(0xFF2E7D32)
    ms < 250 -> Color(0xFFE08A00)
    else -> Color(0xFFD32F2F)
}
