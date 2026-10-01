package com.baystudio.droide.core

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Keep serialized names stable for backward compatibility.


@Serializable
data class DroideTheme(
    val name: String,
    @SerialName("dark") val background: String = "#0D1117",
    @SerialName("light") val foreground: String = "#E6EDF3",
     
    val colors: Map<String, String> = emptyMap(),
)

 
data class DroideUiPalette(
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val error: Color,
    val success: Color,
    val warning: Color,
    val purple: Color,
    val status: Color,
    val statusText: Color,
)

data class DroideTerminalPalette(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val ansi: List<Int>,
)

data class DroideEditorPalette(
    val isDark: Boolean,
    val background: Int,
    val foreground: Int,
    val lineNumber: Int,
    val activeLineNumber: Int,
    val selection: Int,
    val currentLine: Int,
    val indentGuide: Int,
    val activeIndentGuide: Int,
    val keyword: Int,
    val comment: Int,
    val literal: Int,
    val operator: Int,
    val function: Int,
    val type: Int,
    val variable: Int,
)

data class DroideThemeSnapshot(
    val name: String,
    val fingerprint: String,
    val isDark: Boolean,
    val colorScheme: ColorScheme,
    val ui: DroideUiPalette,
    val terminal: DroideTerminalPalette,
    val editor: DroideEditorPalette,
)

@Serializable
private data class ThemeState(val current: String)

class ThemeManager(private val workDir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val stateFile = PathSecurity.resolveWithin(workDir, ".droide/theme.json")
    private val customDir = PathSecurity.resolveWithin(workDir, ".droide/themes")
    private val builtins = listOf(
        DroideTheme(
            name = "droide-dark",
            background = "#0D1117",
            foreground = "#E6EDF3",
            colors = mapOf(
                "sideBar.background" to "#161B22",
                "panel.background" to "#161B22",
                "activityBar.background" to "#0D1117",
                "statusBar.background" to "#0B6BBB",
                "panel.border" to "#30363D",
                "descriptionForeground" to "#8B949E",
                "focusBorder" to "#58A6FF",
                

                "editor.background" to "#1E1E1E",
                "editor.foreground" to "#D4D4D4",
                "editorLineNumber.foreground" to "#858585",
                "editorLineNumber.activeForeground" to "#C6C6C6",
                "editor.selectionBackground" to "#264F78",
                "editor.lineHighlightBackground" to "#2A2D2E",
                "editorIndentGuide.background1" to "#404040",
                "editorIndentGuide.activeBackground1" to "#707070",
            ),
        ),
        DroideTheme(
            name = "droide-light",
            background = "#F6F8FA",
            foreground = "#24292F",
            colors = mapOf(
                "sideBar.background" to "#FFFFFF",
                "panel.background" to "#FFFFFF",
                "activityBar.background" to "#F6F8FA",
                "statusBar.background" to "#0969DA",
                "panel.border" to "#D0D7DE",
                "descriptionForeground" to "#57606A",
                "focusBorder" to "#0969DA",
            ),
        ),
        DroideTheme("matrix", "#001100", "#00FF00", mapOf("focusBorder" to "#00FF00")),
        DroideTheme("tokyonight", "#1A1B26", "#C0CAF5", mapOf("focusBorder" to "#7AA2F7", "descriptionForeground" to "#9AA5CE")),
    )
    private var current = builtins.first()

    fun list(): List<DroideTheme> = (builtins + DeclarativeThemeRegistry.list() + loadCustom())
        .filter { theme ->
            theme.name.isNotBlank() && theme.name.length <= 80 &&
                validColor(theme.background) && validColor(theme.foreground) &&
                theme.colors.size <= 96 && theme.colors.all { (key, value) -> key.length <= 96 && validColor(value) }
        }
        .distinctBy { it.name }

    fun current(): DroideTheme {
        loadPersisted()
        return current
    }

    fun select(name: String) {
        list().firstOrNull { it.name == name }?.let {
            current = it
            persist(it.name)
        }
    }

     
    fun snapshot(): DroideThemeSnapshot = buildSnapshot(current())

    // Compatibility shim for old callers.
    @Suppress("UNUSED_PARAMETER")
    fun toColorScheme(isDark: Boolean): ColorScheme = snapshot().colorScheme

    private fun persist(name: String) = runCatching {
        stateFile.parentFile?.mkdirs()
        atomicWrite(stateFile, json.encodeToString(ThemeState(name.take(80))))
    }

    private fun loadPersisted() {
        runCatching {
            if (!stateFile.isFile || stateFile.length() > 16_000) return@runCatching
            val name = json.decodeFromString<ThemeState>(stateFile.readText()).current
            (builtins + DeclarativeThemeRegistry.list() + loadCustom()).firstOrNull { it.name == name }?.let { current = it }
        }
    }

    private fun buildSnapshot(theme: DroideTheme): DroideThemeSnapshot {
        val math = ThemeContrastPolicy
        

        val background = math.opaque(parseColor(theme.background))
        val requestedForeground = math.opaque(parseColor(theme.foreground))
        val foreground = math.ensureTextContrast(background, requestedForeground, 4.5)
        val dark = math.luminance(background) < 0.42
        val editorBackground = math.opaque(parseColor(theme.color("editor.background") ?: theme.background))
        val requestedEditorForeground = math.opaque(parseColor(theme.color("editor.foreground") ?: theme.foreground))
        val editorForeground = math.ensureTextContrast(editorBackground, requestedEditorForeground, 4.5)
        val editorDark = math.luminance(editorBackground) < 0.42

        fun optional(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key -> theme.color(key)?.let(::parseColor) }
        fun colorOr(fallback: Int, vararg keys: String): Int = optional(*keys) ?: fallback

        val surface = math.opaque(colorOr(math.mix(background, foreground, if (dark) 0.055 else 0.025), "sideBar.background", "panel.background"))
        val surface2 = math.opaque(colorOr(math.mix(background, foreground, if (dark) 0.085 else 0.05), "editorWidget.background", "panel.background"))
        val surface3 = math.opaque(colorOr(math.mix(background, foreground, if (dark) 0.12 else 0.085), "activityBar.background"))
        val border = math.opaque(colorOr(math.mix(background, foreground, if (dark) 0.18 else 0.18), "panel.border", "sideBar.border"))
        val borderStrong = math.mix(background, foreground, if (dark) 0.25 else 0.28)
        val mutedRequested = math.opaque(colorOr(math.mix(foreground, background, 0.43), "descriptionForeground"))
        val muted = math.ensureTextContrast(background, mutedRequested, 3.0)
        val primaryRequested = math.opaque(colorOr(if (dark) 0xFF58A6FF.toInt() else 0xFF0969DA.toInt(), "focusBorder", "textLink.foreground", "button.background"))
        val primary = math.ensureAccentVisibility(background, primaryRequested)
        val error = math.opaque(colorOr(if (dark) 0xFFF85149.toInt() else 0xFFCF222E.toInt(), "errorForeground"))
        val success = math.opaque(colorOr(if (dark) 0xFF3FB950.toInt() else 0xFF1A7F37.toInt(), "testing.iconPassed"))
        val warning = math.opaque(colorOr(if (dark) 0xFFD29922.toInt() else 0xFF9A6700.toInt(), "editorWarning.foreground"))
        val purple = math.opaque(colorOr(if (dark) 0xFFBC8CFF.toInt() else 0xFF8250DF.toInt(), "symbolIcon.classForeground"))
        val status = math.opaque(colorOr(primary, "statusBar.background"))

        val ui = DroideUiPalette(
            background = Color(background),
            surface = Color(surface),
            surface2 = Color(surface2),
            surface3 = Color(surface3),
            border = Color(border),
            borderStrong = Color(borderStrong),
            text = Color(foreground),
            muted = Color(muted),
            primary = Color(primary),
            error = Color(error),
            success = Color(success),
            warning = Color(warning),
            purple = Color(purple),
            status = Color(status),
            statusText = Color(math.bestMonochromeForeground(status)),
        )

        val onPrimary = Color(math.bestMonochromeForeground(primary))
        val colorScheme = if (dark) {
            darkColorScheme(
                primary = ui.primary,
                onPrimary = onPrimary,
                primaryContainer = Color(math.mix(background, primary, 0.24)),
                onPrimaryContainer = ui.text,
                secondary = ui.purple,
                onSecondary = Color(math.bestMonochromeForeground(purple)),
                tertiary = ui.warning,
                onTertiary = Color(math.bestMonochromeForeground(warning)),
                background = ui.background,
                onBackground = ui.text,
                surface = ui.surface,
                onSurface = Color(math.ensureTextContrast(surface, foreground, 4.5)),
                surfaceVariant = ui.surface2,
                onSurfaceVariant = Color(math.ensureTextContrast(surface2, muted, 3.0)),
                outline = ui.border,
                outlineVariant = ui.borderStrong,
                error = ui.error,
                onError = Color(math.bestMonochromeForeground(error)),
            )
        } else {
            lightColorScheme(
                primary = ui.primary,
                onPrimary = onPrimary,
                primaryContainer = Color(math.mix(background, primary, 0.14)),
                onPrimaryContainer = Color(math.ensureTextContrast(math.mix(background, primary, 0.14), foreground, 4.5)),
                secondary = ui.purple,
                onSecondary = Color(math.bestMonochromeForeground(purple)),
                tertiary = ui.warning,
                onTertiary = Color(math.bestMonochromeForeground(warning)),
                background = ui.background,
                onBackground = ui.text,
                surface = ui.surface,
                onSurface = Color(math.ensureTextContrast(surface, foreground, 4.5)),
                surfaceVariant = ui.surface2,
                onSurfaceVariant = Color(math.ensureTextContrast(surface2, muted, 3.0)),
                outline = ui.border,
                outlineVariant = ui.borderStrong,
                error = ui.error,
                onError = Color(math.bestMonochromeForeground(error)),
            )
        }

        val terminalBackground = math.opaque(colorOr(background, "terminal.background"))
        val terminalForeground = math.ensureTextContrast(terminalBackground, colorOr(foreground, "terminal.foreground"), 4.5)
        val terminalCursor = math.ensureAccentVisibility(terminalBackground, colorOr(terminalForeground, "terminalCursor.foreground"))
        val ansiKeys = listOf(
            "terminal.ansiBlack", "terminal.ansiRed", "terminal.ansiGreen", "terminal.ansiYellow",
            "terminal.ansiBlue", "terminal.ansiMagenta", "terminal.ansiCyan", "terminal.ansiWhite",
            "terminal.ansiBrightBlack", "terminal.ansiBrightRed", "terminal.ansiBrightGreen", "terminal.ansiBrightYellow",
            "terminal.ansiBrightBlue", "terminal.ansiBrightMagenta", "terminal.ansiBrightCyan", "terminal.ansiBrightWhite",
        )
        val ansiDefaults = if (dark) DARK_ANSI else LIGHT_ANSI
        val ansi = ansiKeys.mapIndexed { index, key -> colorOr(ansiDefaults[index], key) }
        val terminal = DroideTerminalPalette(terminalBackground, terminalForeground, terminalCursor, ansi)

        val editorMuted = math.mix(editorForeground, editorBackground, if (editorDark) 0.48 else 0.42)
        val guideFallback = math.mix(editorBackground, editorForeground, if (editorDark) 0.17 else 0.14)
        val activeGuideFallback = math.mix(editorBackground, editorForeground, if (editorDark) 0.36 else 0.30)
        val editor = DroideEditorPalette(
            isDark = editorDark,
            background = editorBackground,
            foreground = editorForeground,
            lineNumber = colorOr(editorMuted, "editorLineNumber.foreground"),
            activeLineNumber = colorOr(editorForeground, "editorLineNumber.activeForeground"),
            selection = colorOr(math.withAlpha(primary, if (editorDark) 0x66 else 0x44), "editor.selectionBackground"),
            currentLine = colorOr(math.withAlpha(editorForeground, if (editorDark) 0x0D else 0x0A), "editor.lineHighlightBackground"),
            indentGuide = colorOr(guideFallback, "editorIndentGuide.background1", "editorIndentGuide.background"),
            activeIndentGuide = colorOr(activeGuideFallback, "editorIndentGuide.activeBackground1", "editorIndentGuide.activeBackground"),
            keyword = if (editorDark) 0xFFFF7B72.toInt() else 0xFFCF222E.toInt(),
            comment = editorMuted,
            literal = if (editorDark) 0xFFA5D6FF.toInt() else 0xFF0A3069.toInt(),
            operator = editorForeground,
            function = if (editorDark) 0xFFD2A8FF.toInt() else 0xFF8250DF.toInt(),
            type = if (editorDark) 0xFF79C0FF.toInt() else 0xFF953800.toInt(),
            variable = if (editorDark) 0xFFFFA657.toInt() else 0xFF0550AE.toInt(),
        )

        val fingerprint = buildString {
            append(theme.name).append('|').append(dark).append('|')
            append(theme.background).append('|').append(theme.foreground)
            theme.colors.toSortedMap().forEach { (k, v) -> append('|').append(k).append('=').append(v) }
        }.hashCode().toUInt().toString(16)

        return DroideThemeSnapshot(theme.name, fingerprint, dark, colorScheme, ui, terminal, editor)
    }

    private fun loadCustom(): List<DroideTheme> {
        if (!customDir.isDirectory) return emptyList()
        return customDir.listFiles()?.asSequence()
            ?.filter { it.isFile && PathSecurity.contains(workDir, it) && it.extension.equals("json", true) && it.length() <= 32_000 }
            ?.take(100)
            ?.mapNotNull { f -> runCatching { json.decodeFromString<DroideTheme>(f.readText()) }.getOrNull() }
            ?.toList() ?: emptyList()
    }

    private fun DroideTheme.color(key: String): String? = colors[key]?.takeIf(::validColor)

    private fun validColor(value: String): Boolean = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$").matches(value)

    private fun parseColor(value: String): Int = android.graphics.Color.parseColor(value)

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(text)
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private companion object {
        val DARK_ANSI = listOf(
            0xFF484F58.toInt(), 0xFFFF7B72.toInt(), 0xFF3FB950.toInt(), 0xFFD29922.toInt(),
            0xFF58A6FF.toInt(), 0xFFBC8CFF.toInt(), 0xFF39C5CF.toInt(), 0xFFB1BAC4.toInt(),
            0xFF6E7681.toInt(), 0xFFFFA198.toInt(), 0xFF56D364.toInt(), 0xFFE3B341.toInt(),
            0xFF79C0FF.toInt(), 0xFFD2A8FF.toInt(), 0xFF56D4DD.toInt(), 0xFFFFFFFF.toInt(),
        )
        val LIGHT_ANSI = listOf(
            0xFF24292F.toInt(), 0xFFCF222E.toInt(), 0xFF1A7F37.toInt(), 0xFF9A6700.toInt(),
            0xFF0969DA.toInt(), 0xFF8250DF.toInt(), 0xFF1B7C83.toInt(), 0xFF57606A.toInt(),
            0xFF6E7781.toInt(), 0xFFA40E26.toInt(), 0xFF116329.toInt(), 0xFF7D4E00.toInt(),
            0xFF0550AE.toInt(), 0xFF6639BA.toInt(), 0xFF0A737D.toInt(), 0xFF24292F.toInt(),
        )
    }
}
