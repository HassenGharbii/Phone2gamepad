package com.phone2gamepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Palette {
    val Bg = Color(0xFF0E1013)
    val Surface = Color(0xFF181B20)
    val SurfaceHigh = Color(0xFF22262D)
    val Outline = Color(0xFF3A404A)
    val Text = Color(0xFFE8EAED)
    val TextDim = Color(0xFF9AA1AC)
    val Accent = Color(0xFF4DB6AC)
    val Danger = Color(0xFFEF6F6C)
    val Warn = Color(0xFFE6B450)
    val ButtonA = Color(0xFF58C46A)
    val ButtonB = Color(0xFFE5534B)
    val ButtonX = Color(0xFF4D8FE8)
    val ButtonY = Color(0xFFE6B450)
}

@Composable
fun P2GTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent, onPrimary = Color(0xFF00201D),
            background = Palette.Bg, onBackground = Palette.Text,
            surface = Palette.Surface, onSurface = Palette.Text,
            surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.TextDim,
            outline = Palette.Outline, error = Palette.Danger,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = Palette.Bg, contentColor = Palette.Text, content = content)
    }
}

/** Standard page chrome: title row with optional back action, scrollable content column. */
@Composable
fun Page(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) TextButton(onClick = onBack) { Text("‹ Back") } else Spacer(Modifier.height(48.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

