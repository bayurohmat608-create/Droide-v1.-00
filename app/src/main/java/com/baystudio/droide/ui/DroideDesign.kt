package com.baystudio.droide.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.DroideUiPalette

 
private val DefaultDroidePalette = DroideUiPalette(
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    surface2 = Color(0xFF1D242D),
    surface3 = Color(0xFF222B36),
    border = Color(0xFF30363D),
    borderStrong = Color(0xFF3B434D),
    text = Color(0xFFE6EDF3),
    muted = Color(0xFF8B949E),
    primary = Color(0xFF58A6FF),
    error = Color(0xFFF85149),
    success = Color(0xFF3FB950),
    warning = Color(0xFFD29922),
    purple = Color(0xFFBC8CFF),
    status = Color(0xFF0B6BBB),
    statusText = Color.White,
)





object DroideColors {
    private var active by mutableStateOf(DefaultDroidePalette)

    internal fun install(palette: DroideUiPalette) {
        if (active != palette) active = palette
    }

    val Background: Color get() = active.background
    val Surface: Color get() = active.surface
    val Surface2: Color get() = active.surface2
    val Surface3: Color get() = active.surface3
    val Border: Color get() = active.border
    val BorderStrong: Color get() = active.borderStrong
    val Text: Color get() = active.text
    val Muted: Color get() = active.muted
    val Primary: Color get() = active.primary
    val Error: Color get() = active.error
    val Success: Color get() = active.success
    val Warning: Color get() = active.warning
    val Purple: Color get() = active.purple
    val Status: Color get() = active.status
    val StatusText: Color get() = active.statusText
}

object DroideDimensions {
    val TopBar = 52.dp
    val EditorTabBar = 40.dp
    val ActivityRail = 52.dp
    val Sidebar = 246.dp
    val WideSidebar = 320.dp
    val AgentPanel = 336.dp
    val AgentPanelMin = 288.dp
    val StatusBar = 21.dp
    val BottomPanel = 210.dp
    val BottomPanelMin = 132.dp
    val BottomPanelMax = 360.dp
    val PrimaryTouch = 44.dp
}

private val DefaultDroideScheme = darkColorScheme(
    primary = DefaultDroidePalette.primary,
    onPrimary = Color(0xFF07111C),
    primaryContainer = Color(0xFF132D46),
    onPrimaryContainer = DefaultDroidePalette.text,
    secondary = DefaultDroidePalette.purple,
    onSecondary = Color(0xFF110B18),
    secondaryContainer = DefaultDroidePalette.surface3,
    onSecondaryContainer = DefaultDroidePalette.text,
    tertiary = DefaultDroidePalette.warning,
    onTertiary = Color(0xFF171000),
    background = DefaultDroidePalette.background,
    onBackground = DefaultDroidePalette.text,
    surface = DefaultDroidePalette.surface,
    onSurface = DefaultDroidePalette.text,
    surfaceVariant = DefaultDroidePalette.surface2,
    onSurfaceVariant = DefaultDroidePalette.muted,
    outline = DefaultDroidePalette.border,
    outlineVariant = DefaultDroidePalette.borderStrong,
    error = DefaultDroidePalette.error,
    onError = Color.White,
)

private val DroideShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

@Composable
fun DroideTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DefaultDroideScheme,
        shapes = DroideShapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}
