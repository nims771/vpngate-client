package com.example.vpngateclient.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vpngateclient.ui.theme.Amber
import com.example.vpngateclient.ui.theme.Green
import com.example.vpngateclient.ui.theme.Idle

private val Gold = Color(0xFFFFB300)

enum class PowerState { IDLE, CONNECTING, CONNECTED }

// ---------- Большая кнопка подключения ----------

@Composable
fun PowerButton(state: PowerState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val target = when (state) {
        PowerState.CONNECTED -> Green
        PowerState.CONNECTING -> Amber
        PowerState.IDLE -> Idle
    }
    val color by animateColorAsState(target, tween(500), label = "powerColor")

    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val glowAlpha = when (state) {
        PowerState.CONNECTED -> 0.45f
        PowerState.CONNECTING -> 0.35f
        PowerState.IDLE -> 0.12f
    }

    Box(
        modifier
            .size(270.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = glowAlpha), Color.Transparent),
                        radius = size.minDimension / 2f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (state == PowerState.IDLE) MaterialTheme.colorScheme.surfaceContainerHigh else color,
            border = if (state == PowerState.IDLE) BorderStroke(3.dp, Green.copy(alpha = 0.6f)) else null,
            shadowElevation = 12.dp,
            modifier = Modifier
                .size(200.dp)
                .scale(if (state == PowerState.CONNECTING) pulse else 1f),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val content = if (state == PowerState.IDLE) Green else Color.White
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(78.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when (state) {
                        PowerState.IDLE -> "ПОДКЛЮЧИТЬ"
                        PowerState.CONNECTING -> "ОТМЕНА"
                        PowerState.CONNECTED -> "ОТКЛЮЧИТЬ"
                    },
                    color = content,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ---------- Карточки и строки ----------

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val color = MaterialTheme.colorScheme.surfaceContainer
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
fun FlagBox(flag: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(52.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(contentAlignment = Alignment.Center) { Text(withFlags(flag), fontSize = 28.sp) }
    }
}

@Composable
fun SwitchRow(text: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PingBadge(ping: Long?, pinging: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    when {
        pinging -> CircularProgressIndicator(modifier.size(18.dp), strokeWidth = 2.dp)
        ping == null -> Text(
            "— мс",
            modifier = modifier,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ping < 0 -> Surface(modifier, color = MaterialTheme.colorScheme.errorContainer, shape = shape) {
            Text(
                "нет ответа",
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        else -> Surface(modifier, color = pingColor(ping).copy(alpha = 0.15f), shape = shape) {
            Text(
                "$ping мс",
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = pingColor(ping),
            )
        }
    }
}

@Composable
fun StarIcon(favorite: Boolean) {
    Icon(
        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
        contentDescription = if (favorite) "Убрать из избранного" else "В избранное",
        tint = if (favorite) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) { Text(text) }
}

/**
 * Шрифт для флагов-эмодзи. На Android флаги рисует система; в Windows своих флагов нет
 * (вместо них буквы «TR»), поэтому Windows-версия подставляет сюда шрифт Twemoji.
 */
val LocalFlagFont = staticCompositionLocalOf<FontFamily?> { null }

/** Текст, где символы флагов (региональные индикаторы) набраны шрифтом [LocalFlagFont]. */
@Composable
fun withFlags(text: String): AnnotatedString {
    val font = LocalFlagFont.current ?: return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val chunk = text.substring(i, i + n)
            if (cp in 0x1F1E6..0x1F1FF) withStyle(SpanStyle(fontFamily = font)) { append(chunk) } else append(chunk)
            i += n
        }
    }
}
