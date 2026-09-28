package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

 
object DeclarativeThemeRegistry {
    private const val MAX_THEME_BYTES = 4 * 1024 * 1024

    private val THEME_COLOR_KEYS = listOf(
        "editor.background", "editor.foreground",
        "sideBar.background", "sideBar.border", "panel.background", "panel.border",
        "activityBar.background", "statusBar.background", "editorWidget.background",
        "descriptionForeground", "focusBorder", "textLink.foreground", "button.background",
        "errorForeground", "testing.iconPassed", "editorWarning.foreground", "symbolIcon.classForeground",
        "editorLineNumber.foreground", "editorLineNumber.activeForeground",
        "editor.selectionBackground", "editor.lineHighlightBackground",
        "editorIndentGuide.background", "editorIndentGuide.background1",
        "editorIndentGuide.activeBackground", "editorIndentGuide.activeBackground1",
        "terminal.background", "terminal.foreground", "terminalCursor.foreground",
        "terminal.ansiBlack", "terminal.ansiRed", "terminal.ansiGreen", "terminal.ansiYellow",
        "terminal.ansiBlue", "terminal.ansiMagenta", "terminal.ansiCyan", "terminal.ansiWhite",
        "terminal.ansiBrightBlack", "terminal.ansiBrightRed", "terminal.ansiBrightGreen", "terminal.ansiBrightYellow",
        "terminal.ansiBrightBlue", "terminal.ansiBrightMagenta", "terminal.ansiBrightCyan", "terminal.ansiBrightWhite",
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val byExtension = linkedMapOf<String, List<DroideTheme>>()

    @Synchronized
    fun register(extensionId: String, root: File, contributions: List<DroideThemeContribution>) {
        val themes = contributions.mapNotNull { contribution ->
            val file = resolve(root, contribution.path)
            parse(extensionId, contribution, root, file)
        }
        if (themes.isEmpty()) byExtension.remove(extensionId) else byExtension[extensionId] = themes
    }

    @Synchronized
    fun unregister(extensionId: String) { byExtension.remove(extensionId) }

    @Synchronized
    fun list(): List<DroideTheme> = byExtension.values.flatten().distinctBy(DroideTheme::name)

    private fun parse(extensionId: String, contribution: DroideThemeContribution, packageRoot: File, file: File): DroideTheme? {
        if (!file.isFile || file.length() !in 1..MAX_THEME_BYTES.toLong()) return null
        val root = runCatching { loadThemeObject(packageRoot, file, linkedSetOf(), 0) }.getOrNull() ?: return null
        val colors = root["colors"] as? JsonObject ?: JsonObject(emptyMap())
        val lightTheme = contribution.uiTheme in setOf("vs", "hc-light")
        val background = colors.color("editor.background")
            ?: if (lightTheme) "#FFFFFF" else "#0D1117"
        val foreground = colors.color("editor.foreground")
            ?: if (lightTheme) "#24292F" else "#E6EDF3"
        if (!validColor(background) || !validColor(foreground)) return null
        val propagated = THEME_COLOR_KEYS.mapNotNull { key -> colors.color(key)?.let { key to it } }.toMap()
        return DroideTheme(
            name = "$extensionId:${contribution.id}".take(80),
            background = background,
            foreground = foreground,
            colors = propagated,
        )
    }

    private fun loadThemeObject(packageRoot: File, file: File, seen: MutableSet<String>, depth: Int): JsonObject {
        require(depth <= 32 && file.isFile && file.length() in 1..MAX_THEME_BYTES.toLong()) { "Invalid theme include graph" }
        val canonicalRoot = packageRoot.canonicalFile
        val canonical = file.canonicalFile
        require(canonical.path.startsWith(canonicalRoot.path + File.separator)) { "Theme include escaped extension root" }
        require(seen.add(canonical.path)) { "Theme include cycle" }
        val current = json.parseToJsonElement(BoundedJsonc.stripComments(canonical.readText())) as? JsonObject
            ?: error("Theme must be an object")
        val include = (current["include"] as? JsonPrimitive)?.contentOrNull
        val baseColors = if (include != null) {
            val relativeCurrent = canonical.relativeTo(canonicalRoot).invariantSeparatorsPath
            val resolved = DeclarativeVsixParser.resolveRelativeBundlePath(relativeCurrent, include)
            val parent = loadThemeObject(canonicalRoot, File(canonicalRoot, resolved), seen, depth + 1)
            parent["colors"] as? JsonObject ?: JsonObject(emptyMap())
        } else JsonObject(emptyMap())
        val ownColors = current["colors"] as? JsonObject ?: JsonObject(emptyMap())
        val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(baseColors.size + ownColors.size)
        merged.putAll(baseColors)
        merged.putAll(ownColors)
        return JsonObject(current.toMutableMap().apply { put("colors", JsonObject(merged)) })
    }

    private fun JsonObject.color(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(::validColor)
    private fun validColor(value: String): Boolean = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$").matches(value)

    private fun resolve(root: File, relative: String): File {
        val base = root.canonicalFile
        val target = File(base, DeclarativeVsixParser.normalizeBundlePath(relative)).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "Theme path escaped extension root" }
        return target
    }
}
