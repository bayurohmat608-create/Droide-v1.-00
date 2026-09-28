package com.baystudio.droide.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Project source trees are never used to store these UI choices.
val Context.workbenchPreferences by preferencesDataStore(name = "workbench_cfg")

object WorkbenchPreferences {
    private val WORD_WRAP = booleanPreferencesKey("editor_word_wrap")
    private val HARDWARE_SHORTCUTS = booleanPreferencesKey("hardware_shortcuts")
    private val ACCESSORY_KEYS_COMFORTABLE = booleanPreferencesKey("accessory_keys_comfortable")
    private val LANDSCAPE_FOCUS_MODE = booleanPreferencesKey("landscape_focus_mode")
    private val DETECT_INDENTATION = booleanPreferencesKey("editor_detect_indentation")
    private val INDENT_STYLE = stringPreferencesKey("editor_indent_style")
    private val TAB_WIDTH = intPreferencesKey("editor_tab_width")
    private val INDENT_SIZE = intPreferencesKey("editor_indent_size")
    private val CONTINUATION_INDENT = intPreferencesKey("editor_continuation_indent")

    data class Snapshot(
        val wordWrap: Boolean = false,
        val hardwareShortcuts: Boolean = true,
        val accessoryKeysComfortable: Boolean = false,
        val landscapeFocusMode: Boolean = false,
        val detectIndentation: Boolean = true,
        val indentStyle: IndentStyle = IndentStyle.SPACES,
        val tabWidth: Int = 4,
        val indentSize: Int = 4,
        val continuationIndent: Int = 8,
    ) {
        val codeStyleDefaults: CodeStyleDefaults get() = CodeStyleDefaults(
            detectIndentation = detectIndentation,
            indentStyle = indentStyle,
            tabWidth = tabWidth,
            indentSize = indentSize,
            continuationIndent = continuationIndent,
        ).normalized()
    }

    fun flow(context: Context): Flow<Snapshot> = context.workbenchPreferences.data.map { prefs ->
        Snapshot(
            wordWrap = prefs[WORD_WRAP] ?: false,
            hardwareShortcuts = prefs[HARDWARE_SHORTCUTS] ?: true,
            accessoryKeysComfortable = prefs[ACCESSORY_KEYS_COMFORTABLE] ?: false,
            landscapeFocusMode = prefs[LANDSCAPE_FOCUS_MODE] ?: false,
            detectIndentation = prefs[DETECT_INDENTATION] ?: true,
            indentStyle = runCatching { IndentStyle.valueOf(prefs[INDENT_STYLE] ?: IndentStyle.SPACES.name) }.getOrDefault(IndentStyle.SPACES),
            tabWidth = (prefs[TAB_WIDTH] ?: 4).coerceIn(1, 8),
            indentSize = (prefs[INDENT_SIZE] ?: 4).coerceIn(1, 8),
            continuationIndent = (prefs[CONTINUATION_INDENT] ?: 8).coerceIn(1, 16),
        )
    }

    suspend fun setWordWrap(context: Context, enabled: Boolean) {
        context.workbenchPreferences.edit { it[WORD_WRAP] = enabled }
    }

    suspend fun setHardwareShortcuts(context: Context, enabled: Boolean) {
        context.workbenchPreferences.edit { it[HARDWARE_SHORTCUTS] = enabled }
    }

    suspend fun setAccessoryKeysComfortable(context: Context, enabled: Boolean) {
        context.workbenchPreferences.edit { it[ACCESSORY_KEYS_COMFORTABLE] = enabled }
    }

    suspend fun setLandscapeFocusMode(context: Context, enabled: Boolean) {
        context.workbenchPreferences.edit { it[LANDSCAPE_FOCUS_MODE] = enabled }
    }

    suspend fun setDetectIndentation(context: Context, enabled: Boolean) {
        context.workbenchPreferences.edit { it[DETECT_INDENTATION] = enabled }
    }

    suspend fun setIndentStyle(context: Context, style: IndentStyle) {
        context.workbenchPreferences.edit { it[INDENT_STYLE] = style.name }
    }

    suspend fun setTabWidth(context: Context, value: Int) {
        context.workbenchPreferences.edit { it[TAB_WIDTH] = value.coerceIn(1, 8) }
    }

    suspend fun setIndentSize(context: Context, value: Int) {
        context.workbenchPreferences.edit { it[INDENT_SIZE] = value.coerceIn(1, 8) }
    }

    suspend fun setContinuationIndent(context: Context, value: Int) {
        context.workbenchPreferences.edit { it[CONTINUATION_INDENT] = value.coerceIn(1, 16) }
    }
}
